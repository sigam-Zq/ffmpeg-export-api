package com.example.videoprocess.service;

import com.example.videoprocess.config.MinioConfig;
import com.example.videoprocess.dto.HlsProcessRequest;
import com.example.videoprocess.dto.HlsProcessResponse;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class HlsService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final FFmpeg ffmpeg;
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
            // 2. Probe input video to determine resolution and duration
            FFmpegProbeResult probeResult = ffprobe.probe(formatPath(tempInput.toString()));
            FFmpegStream videoStream = probeResult.getStreams().stream()
                    .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No video stream found"));

            int width = videoStream.width;
            int height = videoStream.height;
            double duration = probeResult.getFormat().duration;
            log.info("Input resolution: {}x{}, duration: {}s", width, height, duration);

            boolean hasAudio = probeResult.getStreams().stream()
                    .anyMatch(s -> s.codec_type == FFmpegStream.CodecType.AUDIO);

            // 3. Determine quality levels
            List<QualityLevel> levels;
            if (request.getResolutions() != null && !request.getResolutions().isEmpty()) {
                levels = new ArrayList<>();
                for (HlsProcessRequest.ResolutionNode node : request.getResolutions()) {
                    // Simple bitrate estimation if not provided: width * height * 0.15 (bits/pixel)
                    // e.g. 1920x1080 * 0.15 = ~3Mbps
                    long estimatedBitrate = (long)(node.getWidth() * node.getHeight() * 1.5); // bit/s? No, usually 0.1 bits per pixel is low quality, 2-3 is high.
                    // Let's use a simpler mapping or the provided bitrate
                    String bitrate = node.getBitrate();
                    if (!StringUtils.hasText(bitrate)) {
                        bitrate = calculateBitrate(node.getWidth(), node.getHeight());
                    }
                    // Estimate maxrate and bufsize
                    String maxrate = bitrate.replace("k", "") + "000"; // simplistic
                    try {
                        long br = Long.parseLong(bitrate.replace("k", ""));
                        maxrate = (br * 12 / 10) + "k"; // 1.2x
                    } catch (Exception ignored) {}
                    
                    levels.add(new QualityLevel(node.getWidth(), node.getHeight(), bitrate, maxrate, bitrate));
                }
            } else {
                levels = determineQualityLevels(width, height);
            }

            // Determine segment duration (hls_time)
            String hlsTime = "6";
            if (request.getSegmentCount() != null && request.getSegmentCount() > 0) {
                // Calculate duration based on count
                double segDuration = duration / request.getSegmentCount();
                hlsTime = String.format("%.3f", segDuration);
            } else if (request.getSegmentDuration() != null && request.getSegmentDuration() > 0) {
                hlsTime = String.valueOf(request.getSegmentDuration());
            }

            // 4. Construct FFmpeg command
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(formatPath(tempInput.toString()));
            
            StringBuilder filterComplex = new StringBuilder();
            StringBuilder varStreamMap = new StringBuilder();
            
            filterComplex.append("[0:v]split=").append(levels.size());
            for (int i = 0; i < levels.size(); i++) {
                filterComplex.append("[v").append(i).append("]");
            }
            filterComplex.append(";");

            if (hasAudio) {
                filterComplex.append("[0:a]asplit=").append(levels.size());
                for (int i = 0; i < levels.size(); i++) {
                    filterComplex.append("[a").append(i).append("]");
                }
                filterComplex.append(";");
            }

            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);
                filterComplex.append("[v").append(i).append("]scale=w=").append(level.width()).append(":h=").append(level.height()).append("[v").append(i).append("out]");
                if (i < levels.size() - 1 || hasAudio) {
                    filterComplex.append(";");
                }
            }
            
            cmd.add("-filter_complex");
            cmd.add(filterComplex.toString());

            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);
                cmd.add("-map");
                cmd.add("[v" + i + "out]");
                cmd.add("-c:v:" + i);
                cmd.add("libx264");
                cmd.add("-b:v:" + i);
                cmd.add(level.bitrate());
                cmd.add("-maxrate:v:" + i);
                cmd.add(level.maxrate());
                cmd.add("-bufsize:v:" + i);
                cmd.add(level.bufsize());
                cmd.add("-pix_fmt:v:" + i);
                cmd.add("yuv420p");
                cmd.add("-g:v:" + i);
                cmd.add("60");
                cmd.add("-keyint_min:v:" + i);
                cmd.add("60");
                cmd.add("-sc_threshold:v:" + i);
                cmd.add("0");

                if (hasAudio) {
                    cmd.add("-map");
                    cmd.add("[a" + i + "]");
                    cmd.add("-c:a:" + i);
                    cmd.add("aac");
                    cmd.add("-b:a:" + i);
                    cmd.add("128k");
                    cmd.add("-ac");
                    cmd.add("2");
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

    private String calculateBitrate(int width, int height) {
        // Simple approximation
        long pixels = (long) width * height;
        if (pixels >= 3840 * 2160) return "10000k";
        if (pixels >= 2560 * 1440) return "6000k";
        if (pixels >= 1920 * 1080) return "4000k";
        if (pixels >= 1280 * 720) return "2000k";
        if (pixels >= 854 * 480) return "1000k";
        return "600k";
    }


    private List<QualityLevel> determineQualityLevels(int width, int height) {
        List<QualityLevel> allLevels = new ArrayList<>();
        // Define standard levels
        allLevels.add(new QualityLevel(3840, 2160, "10000k", "12000k", "20000k")); // 4K
        allLevels.add(new QualityLevel(2560, 1440, "6000k", "8000k", "12000k"));   // 2K
        allLevels.add(new QualityLevel(1920, 1080, "4000k", "5000k", "8000k"));    // 1080p
        allLevels.add(new QualityLevel(1280, 720,  "2000k", "2500k", "4000k"));    // 720p
        allLevels.add(new QualityLevel(854, 480,   "1000k", "1200k", "2000k"));    // 480p
        allLevels.add(new QualityLevel(640, 360,   "600k",  "800k",  "1200k"));    // 360p

        // Filter levels that are <= input resolution
        List<QualityLevel> validLevels = new ArrayList<>();
        for (QualityLevel level : allLevels) {
            if (level.width <= width && level.height <= height) {
                validLevels.add(level);
            }
        }
        
        // If input is smaller than 360p, just add original
        if (validLevels.isEmpty()) {
            validLevels.add(new QualityLevel(width, height, "500k", "600k", "1000k"));
        }
        
        // Requirement says "require three bitrates".
        // We take top 3 valid levels.
        return validLevels.stream().limit(3).toList();
    }

    private record QualityLevel(int width, int height, String bitrate, String maxrate, String bufsize) {}

    private Path downloadFile(String objectName) throws Exception {
        Path tempFile = Files.createTempFile("input-", ".tmp");
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(objectName)
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
