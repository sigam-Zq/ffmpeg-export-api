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
            // 2. Probe input video to determine resolution
            FFmpegProbeResult probeResult = ffprobe.probe(formatPath(tempInput.toString()));
            FFmpegStream videoStream = probeResult.getStreams().stream()
                    .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No video stream found"));

            int width = videoStream.width;
            int height = videoStream.height;
            log.info("Input resolution: {}x{}", width, height);

            boolean hasAudio = probeResult.getStreams().stream()
                    .anyMatch(s -> s.codec_type == FFmpegStream.CodecType.AUDIO);
            log.info("Input has audio: {}", hasAudio);

            // 3. Determine quality levels based on input resolution
            // Levels: 4K (2160p), 2K (1440p), 1080p, 720p, 480p, 360p
            List<QualityLevel> levels = determineQualityLevels(width, height);

            // 4. Construct FFmpeg command for multi-bitrate HLS
            // We use raw ProcessBuilder because net.bramp.ffmpeg doesn't easily support complex filter_complex map logic for HLS variants
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegPath);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(formatPath(tempInput.toString()));
            
            // Build filter_complex and maps
            StringBuilder filterComplex = new StringBuilder();
            StringBuilder varStreamMap = new StringBuilder();
            
            // Split input video stream into N streams and handle audio
            filterComplex.append("[0:v]split=").append(levels.size());
            for (int i = 0; i < levels.size(); i++) {
                filterComplex.append("[v").append(i).append("]");
            }
            filterComplex.append(";");

            if (hasAudio) {
                // Split audio stream into N streams so each variant has its own independent audio packet stream
                filterComplex.append("[0:a]asplit=").append(levels.size());
                for (int i = 0; i < levels.size(); i++) {
                    filterComplex.append("[a").append(i).append("]");
                }
                filterComplex.append(";");
            }

            // Scale each video stream
            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);
                filterComplex.append("[v").append(i).append("]scale=w=").append(level.width()).append(":h=").append(level.height()).append("[v").append(i).append("out]");
                if (i < levels.size() - 1 || hasAudio) {
                    filterComplex.append(";");
                }
            }
            
            cmd.add("-filter_complex");
            cmd.add(filterComplex.toString());

            // Map streams for each variant
            for (int i = 0; i < levels.size(); i++) {
                QualityLevel level = levels.get(i);
                
                // Video map for this variant
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
                
                // Ensure consistent GOP and pixel format for HLS compatibility
                cmd.add("-pix_fmt:v:" + i);
                cmd.add("yuv420p");
                cmd.add("-g:v:" + i);
                cmd.add("60");
                cmd.add("-keyint_min:v:" + i);
                cmd.add("60");
                cmd.add("-sc_threshold:v:" + i);
                cmd.add("0");

                // Audio map for this variant
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

            // HLS settings
            cmd.add("-var_stream_map");
            cmd.add(varStreamMap.toString());
            cmd.add("-hls_time");
            cmd.add("6"); // Reduced from 10 to be more compatible with short videos
            cmd.add("-hls_playlist_type");
            cmd.add("vod");
            cmd.add("-hls_flags");
            cmd.add("independent_segments");
            
            // Master playlist name
            cmd.add("-master_pl_name");
            cmd.add("index.m3u8");
            
            // Segment filename pattern
            // stream_%v/data%03d.ts
            cmd.add("-hls_segment_filename");
            cmd.add(formatPath(outputDir.resolve("stream_%v_data%03d.ts").toString()));
            
            // Variant playlist pattern
            // stream_%v.m3u8
            cmd.add(formatPath(outputDir.resolve("stream_%v.m3u8").toString()));

            log.info("Executing HLS command: {}", String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Consume output (both stdout and stderr because of redirectErrorStream(true))
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (log.isDebugEnabled()) {
                        log.debug(line);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("FFmpeg HLS failed with exit code {}. Output:\n{}", exitCode, output);
                throw new RuntimeException("FFmpeg HLS conversion failed with exit code " + exitCode + ". See logs for details.");
            }

            // 5. Upload all generated files to MinIO
            // We need to upload: index.m3u8, stream_*.m3u8, stream_*_data*.ts
            String targetPath = request.getTargetPath();
            if (targetPath.endsWith("/")) {
                targetPath = targetPath.substring(0, targetPath.length() - 1);
            }
            
            // Ensure target path structure (e.g., "movies/movie1")
            
            List<HlsProcessResponse.StreamInfo> streamInfos = new ArrayList<>();
            
            // Walk the output directory
            try (java.util.stream.Stream<Path> paths = Files.walk(outputDir)) {
                String finalTargetPath = targetPath;
                paths.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String filename = file.getFileName().toString();
                        String objectName = finalTargetPath + "/" + filename;
                        
                        // Upload
                        uploadFileToMinio(file, objectName);
                        
                        // If it's a variant playlist, add to response info
                        if (filename.startsWith("stream_") && filename.endsWith(".m3u8")) {
                            // Extract index from filename stream_0.m3u8
                            int index = Integer.parseInt(filename.substring(7, filename.lastIndexOf('.')));
                            if (index < levels.size()) {
                                QualityLevel level = levels.get(index);
                                String resolution = level.width + "x" + level.height;
                                String url = getFullUrl(objectName);
                                streamInfos.add(new HlsProcessResponse.StreamInfo(resolution, objectName, url));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to upload file {}", file, e);
                    }
                });
            }
            
            // Also add master playlist to response (optional, or just return the variants)
            // The requirement says "return three different bitrate files index.m3u8 path and resolution"
            // Usually the client plays the master index.m3u8.
            // Let's add the master index as well if needed, but the structure asks for specific streams.
            // We'll stick to the variants as requested, but also including the master might be useful.
            
            // Let's strictly follow: "return three different bitrate files index.m3u8 path and corresponding resolution"
            // Note: The master index.m3u8 doesn't have a single resolution, it links to others.
            // The generated files are stream_0.m3u8, stream_1.m3u8 etc.
            
            return new HlsProcessResponse(streamInfos);

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
