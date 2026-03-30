package com.example.videoprocess.service;

import com.example.videoprocess.config.MinioConfig;
import com.example.videoprocess.dto.HlsProcessRequest;
import com.example.videoprocess.dto.HlsProcessResponse;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HlsService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final FFprobe ffprobe;

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;


    private String formatPath(String path) {
        if (path == null) return null;
        // FFmpeg handles forward slashes well on all platforms including Windows
        // But for some filters and protocols on Windows, escaping is required.
        // For general file paths, converting backslashes to forward slashes is safest for FFmpeg CLI.
        return path.replace("\\", "/");
    }

    public HlsProcessResponse processHls(HlsProcessRequest request) throws Exception {
        // 1. Download input file
        Path tempInput = downloadFile(request.getObjectName());
        Path outputDir = Files.createTempDirectory("hls-output-");

        try {
            // 2. Probe input video to determine resolution, fps and duration
            FFmpegProbeResult probeResult = ffprobe.probe(formatPath(tempInput.toString()));
            FFmpegStream videoStream = probeResult.getStreams().stream()
                    .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No video stream found"));

            int width = videoStream.width;
            int height = videoStream.height;
            double duration = probeResult.getFormat().duration;

            // Calculate real fps from avg_frame_rate (e.g. 30000/1001 ≈ 29.97)
            double fps = 25.0; // safe default
            if (videoStream.avg_frame_rate != null && videoStream.avg_frame_rate.getDenominator() > 0) {
                fps = (double) videoStream.avg_frame_rate.getNumerator()
                        / videoStream.avg_frame_rate.getDenominator();
            }
            log.info("Input resolution: {}x{}, fps: {}, duration: {}s", width, height,
                    String.format("%.3f", fps), duration);

            boolean hasAudio = probeResult.getStreams().stream()
                    .anyMatch(s -> s.codec_type == FFmpegStream.CodecType.AUDIO);

            // 3. Determine quality levels
            List<QualityLevel> levels;
            if (request.getResolutions() != null && !request.getResolutions().isEmpty()) {
                levels = new ArrayList<>();
                for (HlsProcessRequest.ResolutionNode node : request.getResolutions()) {
                    String bitrate = StringUtils.hasText(node.getBitrate())
                            ? node.getBitrate()
                            : calculateBitrate(node.getWidth(), node.getHeight());
                    String maxrate = StringUtils.hasText(node.getMaxrate())
                            ? node.getMaxrate()
                            : deriveMaxrate(bitrate);
                    String bufsize = StringUtils.hasText(node.getBufsize())
                            ? node.getBufsize()
                            : deriveBufsize(bitrate);
                    levels.add(new QualityLevel(node.getWidth(), node.getHeight(), bitrate, maxrate, bufsize));
                }
            } else {
                levels = determineQualityLevels(width, height);
            }

            // Determine segment duration (hls_time)
            // For best compatibility, segment duration should align to GOP size (= fps * hlsTimeSeconds)
            int hlsTimeSeconds = 6; // default
            if (request.getSegmentCount() != null && request.getSegmentCount() > 0) {
                // Round to nearest integer so GOP boundaries align
                hlsTimeSeconds = (int) Math.max(1, Math.round(duration / request.getSegmentCount()));
            } else if (request.getSegmentDuration() != null && request.getSegmentDuration() > 0) {
                hlsTimeSeconds = request.getSegmentDuration();
            }
            String hlsTime = String.valueOf(hlsTimeSeconds);

            // GOP size = fps * segment_duration (integer).
            // keyint_min = GOP size ensures every segment starts with a keyframe.
            // sc_threshold=0 disables scene-cut keyframes that would break alignment.
            int gopSize = (int) Math.round(fps * hlsTimeSeconds);
            if (gopSize < 1) gopSize = 1;
            log.info("HLS segment={}s, fps={}, GOP={}", hlsTimeSeconds, String.format("%.3f", fps), gopSize);

            // Encoding preset (speed/quality trade-off for libx264)
            String preset = StringUtils.hasText(request.getPreset()) ? request.getPreset() : "fast";

            // Audio bitrate
            int audioBitrate = request.getAudioBitrate() != null ? request.getAudioBitrate() : 128;

            // 4. Construct FFmpeg command
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(formatPath(tempInput.toString()));

            // filter_complex: split video (and audio) into N streams, scale each
            List<String> filterParts = new ArrayList<>();

            StringBuilder vSplit = new StringBuilder();
            vSplit.append("[0:v]split=").append(levels.size());
            for (int i = 0; i < levels.size(); i++) {
                vSplit.append("[vin").append(i).append("]");
            }
            filterParts.add(vSplit.toString());

            if (hasAudio && levels.size() > 1) {
                StringBuilder aSplit = new StringBuilder();
                aSplit.append("[0:a]asplit=").append(levels.size());
                for (int i = 0; i < levels.size(); i++) {
                    aSplit.append("[ain").append(i).append("]");
                }
                filterParts.add(aSplit.toString());
            }

            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);
                // force_original_aspect_ratio=decrease: fit within the box without upscaling
                // scale=-2:720 keeps aspect ratio with height=720, width aligned to mod 2
                // Using lanczos for high-quality downscale
                String scaleFilter = String.format(
                        "[vin%d]scale=w=%d:h=%d:force_original_aspect_ratio=decrease:flags=lanczos,pad=%d:%d:(ow-iw)/2:(oh-ih)/2[vout%d]",
                        i, level.width(), level.height(), level.width(), level.height(), i);
                filterParts.add(scaleFilter);
            }

            cmd.add("-filter_complex");
            cmd.add(String.join(";", filterParts));

            StringBuilder varStreamMap = new StringBuilder();
            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);

                cmd.add("-map");
                cmd.add("[vout" + i + "]");
                cmd.add("-c:v:" + i);
                cmd.add("libx264");
                cmd.add("-preset:v:" + i);
                cmd.add(preset);
                cmd.add("-b:v:" + i);
                cmd.add(level.bitrate());
                cmd.add("-maxrate:v:" + i);
                cmd.add(level.maxrate());
                cmd.add("-bufsize:v:" + i);
                cmd.add(level.bufsize());
                cmd.add("-pix_fmt:v:" + i);
                cmd.add("yuv420p");
                // GOP aligned to segment duration for clean HLS cuts
                cmd.add("-g:v:" + i);
                cmd.add(String.valueOf(gopSize));
                cmd.add("-keyint_min:v:" + i);
                cmd.add(String.valueOf(gopSize));
                cmd.add("-sc_threshold:v:" + i);
                cmd.add("0");
                // H.264 main profile, level 4.0 – broad device compatibility
                cmd.add("-profile:v:" + i);
                cmd.add("main");

                if (hasAudio) {
                    String audioMap = (levels.size() > 1) ? "[ain" + i + "]" : "0:a";
                    cmd.add("-map");
                    cmd.add(audioMap);
                    cmd.add("-c:a:" + i);
                    cmd.add("aac");
                    cmd.add("-b:a:" + i);
                    cmd.add(audioBitrate + "k");
                    cmd.add("-ac:a:" + i);
                    cmd.add("2");
                    // AAC-LC for maximum compatibility
                    cmd.add("-profile:a:" + i);
                    cmd.add("aac_low");
                }

                if (i > 0) varStreamMap.append(" ");
                varStreamMap.append("v:").append(i);
                if (hasAudio) {
                    varStreamMap.append(",a:").append(i);
                }
            }

            cmd.add("-var_stream_map");
            cmd.add(varStreamMap.toString());
            cmd.add("-hls_time");
            cmd.add(hlsTime);
            cmd.add("-hls_playlist_type");
            cmd.add("vod");
            cmd.add("-hls_flags");
            cmd.add("independent_segments");

            cmd.add("-master_pl_name");
            cmd.add("master.m3u8");

            // Use v%v as temporary folder name
            cmd.add("-hls_segment_filename");
            cmd.add(formatPath(outputDir.resolve("v%v/segment%03d.ts").toString()));

            cmd.add(formatPath(outputDir.resolve("v%v/index.m3u8").toString()));

            log.info("Executing HLS command: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder processOutput = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("FFmpeg HLS failed with exit code {}. Output:\n{}", exitCode, processOutput);
                throw new RuntimeException("FFmpeg HLS conversion failed with exit code " + exitCode);
            }

            // 5. Upload files with structure
            String targetPath = request.getTargetPath();
            if (targetPath.endsWith("/")) targetPath = targetPath.substring(0, targetPath.length() - 1);
            final String finalTargetPath = targetPath;
            
            // Rewrite master.m3u8
            Path masterPath = outputDir.resolve("master.m3u8");
            String masterContent = Files.readString(masterPath);
            
            List<HlsProcessResponse.StreamInfo> streamInfos = new ArrayList<>();
            
            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);
                String resolution = level.width() + "x" + level.height();
                String tempFolder = "v" + i;
                String targetFolder = resolution;
                
                // If duplicates, append index
                // (Simplified: assuming unique resolutions or okay to overwrite if same)
                
                // Replace in master playlist
                // Regex to replace "v0/" with "1920x1080/"
                masterContent = masterContent.replace(tempFolder + "/", targetFolder + "/");
                
                // Upload folder contents
                Path levelDir = outputDir.resolve(tempFolder);
                if (Files.exists(levelDir)) {
                    try (java.util.stream.Stream<Path> files = Files.walk(levelDir)) {
                        files.filter(Files::isRegularFile).forEach(file -> {
                            try {
                                String filename = file.getFileName().toString();
                                String objectName = finalTargetPath + "/" + targetFolder + "/" + filename;
                                uploadFileToMinio(file, objectName);
                                
                                if (filename.endsWith(".m3u8")) {
                                    String url = getFullUrl(objectName);
                                    streamInfos.add(new HlsProcessResponse.StreamInfo(resolution, objectName, url));
                                }
                            } catch (Exception e) {
                                log.error("Upload failed", e);
                            }
                        });
                    }
                }
            }
            
            // Upload rewritten master.m3u8
            Path tempMaster = Files.createTempFile("master-rewritten", ".m3u8");
            Files.writeString(tempMaster, masterContent);
            String masterObjectName = finalTargetPath + "/master.m3u8";
            uploadFileToMinio(tempMaster, masterObjectName);
            Files.deleteIfExists(tempMaster);
            
            HlsProcessResponse response = new HlsProcessResponse();
            response.setMasterM3u8Path(masterObjectName);
            response.setMasterM3u8Url(getFullUrl(masterObjectName));
            response.setStreams(streamInfos);
            
            return response;

        } finally {
            // Cleanup temp dir
            try (java.util.stream.Stream<Path> paths = Files.walk(outputDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
            Files.deleteIfExists(tempInput);
        }
    }

    /**
     * Calculate a recommended target bitrate based on resolution.
     * These values are tuned for libx264 with "fast" preset at standard quality.
     */
    private String calculateBitrate(int width, int height) {
        long pixels = (long) width * height;
        if (pixels >= 3840L * 2160) return "10000k"; // 4K
        if (pixels >= 2560L * 1440) return "6000k";  // 2K
        if (pixels >= 1920L * 1080) return "4000k";  // 1080p
        if (pixels >= 1280L * 720)  return "2000k";  // 720p
        if (pixels >= 854L  * 480)  return "1000k";  // 480p
        return "600k";                                 // 360p and below
    }

    /** maxrate = 1.2× bitrate (allows burst above average). */
    private String deriveMaxrate(String bitrate) {
        try {
            long kbps = Long.parseLong(bitrate.replace("k", "").trim());
            return (kbps * 12 / 10) + "k";
        } catch (Exception e) {
            return bitrate;
        }
    }

    /** bufsize = 2× bitrate (VBV buffer for ~2 seconds of video). */
    private String deriveBufsize(String bitrate) {
        try {
            long kbps = Long.parseLong(bitrate.replace("k", "").trim());
            return (kbps * 2) + "k";
        } catch (Exception e) {
            return bitrate;
        }
    }


    private List<QualityLevel> determineQualityLevels(int width, int height) {
        // Standard quality ladder – each level uses correct maxrate/bufsize
        List<QualityLevel> allLevels = new ArrayList<>();
        allLevels.add(new QualityLevel(3840, 2160, "10000k", "12000k", "20000k")); // 4K
        allLevels.add(new QualityLevel(2560, 1440, "6000k",  "7200k",  "12000k")); // 2K
        allLevels.add(new QualityLevel(1920, 1080, "4000k",  "4800k",  "8000k"));  // 1080p
        allLevels.add(new QualityLevel(1280, 720,  "2000k",  "2400k",  "4000k"));  // 720p
        allLevels.add(new QualityLevel(854,  480,  "1000k",  "1200k",  "2000k"));  // 480p
        allLevels.add(new QualityLevel(640,  360,  "600k",   "720k",   "1200k"));  // 360p

        // Keep only levels whose width AND height do not exceed the source
        List<QualityLevel> validLevels = allLevels.stream()
                .filter(l -> l.width() <= width && l.height() <= height)
                .toList();

        // If input is smaller than 360p, generate a single level at source resolution
        if (validLevels.isEmpty()) {
            String bitrate = calculateBitrate(width, height);
            validLevels = List.of(new QualityLevel(width, height, bitrate,
                    deriveMaxrate(bitrate), deriveBufsize(bitrate)));
        }

        // Return at most 3 quality levels (top quality first)
        return validLevels.stream().limit(3).toList();
    }

    private record QualityLevel(int width, int height, String bitrate, String maxrate, String bufsize) {}

    private Path downloadFile(String objectName) throws Exception {
        String key = objectName == null ? "" : objectName.trim();
        if (key.startsWith("/")) {
            key = key.substring(1);
        }
        key = key.replace("\\", "/");
        while (key.contains("//")) {
            key = key.replace("//", "/");
        }
        log.info("Downloading from MinIO bucket='{}', object='{}'", minioConfig.getBucketName(), key);
        Path tempFile = Files.createTempFile("input-", ".tmp");
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(key)
                        .build())) {
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private void uploadFileToMinio(Path file, String objectName) throws Exception {
        try (InputStream uploadStream = new FileInputStream(file.toFile())) {
            String contentType = "application/octet-stream";
            if (objectName.endsWith(".m3u8")) contentType = "application/x-mpegURL";
            else if (objectName.endsWith(".ts")) contentType = "video/MP2T";
            
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
                            .stream(uploadStream, file.toFile().length(), -1)
                            .contentType(contentType)
                            .build());
        }
    }
    
    private String getFullUrl(String objectName) {
        String endpoint = StringUtils.hasText(minioConfig.getExternalEndpoint()) 
                ? minioConfig.getExternalEndpoint() 
                : minioConfig.getEndpoint();
                
        if (!endpoint.endsWith("/")) { 
            endpoint += "/";
        }
        return endpoint + minioConfig.getBucketName() + "/" + objectName;
    }
}
