package com.example.videoprocess.service;

import com.example.videoprocess.config.MinioConfig;
import com.example.videoprocess.dto.AudioProcessRequest;
import com.example.videoprocess.dto.ImageProcessRequest;
import com.example.videoprocess.dto.VideoProcessRequest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.builder.FFmpegOutputBuilder;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final FFmpeg ffmpeg;
    private final FFprobe ffprobe;


    // Helper to download file
    private Path downloadFile(String objectName) throws Exception {
        Path tempFile = Files.createTempFile("input-", ".tmp");
        log.info("Downloading {} from MinIO bucket {}", objectName, minioConfig.getBucketName());
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .object(objectName)
                        .build())) {
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    // Helper to upload file
    private String uploadFile(Path file, String extension) throws Exception {
        String outputObjectName = "tmp/" + UUID.randomUUID() + (extension.startsWith(".") ? extension : "." + extension);
        log.info("Uploading result to MinIO as {}", outputObjectName);
        try (InputStream uploadStream = new FileInputStream(file.toFile())) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(outputObjectName)
                            .stream(uploadStream, file.toFile().length(), -1)
                            // Content type could be guessed, but optional for now
                            .build());
        }
        return getFullUrl(outputObjectName);
    }

    private Path preprocessSubtitle(Path inputPath, com.example.videoprocess.dto.SubtitleOptions options,
                                    int outputWidth, int outputHeight) {
        try {
            Path cleanSrtPath = Files.createTempFile("clean-", ".srt");
            List<int[]> positions = new ArrayList<>();

            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(.*-->.*)\\s+X:(\\d+)\\s+Y:(\\d+).*");
            
            try (java.io.BufferedReader reader = Files.newBufferedReader(inputPath);
                 java.io.BufferedWriter writer = Files.newBufferedWriter(cleanSrtPath)) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    java.util.regex.Matcher matcher = pattern.matcher(line);
                    if (matcher.matches()) {
                        String cleanTimestamp = matcher.group(1).trim();
                        int x = Integer.parseInt(matcher.group(2));
                        int y = Integer.parseInt(matcher.group(3));
                        
                        writer.write(cleanTimestamp);
                        writer.newLine();
                        
                        positions.add(new int[]{x, y});
                    } else if (line.contains("-->")) {
                         writer.write(line);
                         writer.newLine();
                         positions.add(null);
                    } else {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }

            Path assPath = Files.createTempFile("converted-", ".ass");
            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(cleanSrtPath.toString())
                    .overrideOutputFiles(true)
                    .addOutput(assPath.toString())
                    .setFormat("ass")
                    .done();
            
            new FFmpegExecutor(ffmpeg, ffprobe).createJob(builder).run();
            
            Path finalAssPath = Files.createTempFile("final-", ".ass");
            try (java.io.BufferedReader reader = Files.newBufferedReader(assPath);
                 java.io.BufferedWriter writer = Files.newBufferedWriter(finalAssPath)) {
                
                String line;
                int eventIndex = 0;
                boolean inEvents = false;
                int playResX = -1;
                int playResY = -1;
                
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("PlayResX")) {
                        int idx = trimmed.indexOf(':');
                        if (idx > 0) {
                            try {
                                playResX = Integer.parseInt(trimmed.substring(idx + 1).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        writer.write(line);
                    } else if (trimmed.startsWith("PlayResY")) {
                        int idx = trimmed.indexOf(':');
                        if (idx > 0) {
                            try {
                                playResY = Integer.parseInt(trimmed.substring(idx + 1).trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        writer.write(line);
                    } else if (trimmed.startsWith("Style: Default")) {
                        String fontName = "Noto Sans CJK SC";
                        int fontSize = (options != null && options.getFontSize() != null) ? options.getFontSize() : 24;
                        String primaryColor = (options != null && StringUtils.hasText(options.getFontColor())) ? options.getFontColor() : "&HFFFFFF";
                        if (primaryColor.startsWith("#")) {
                             String r = primaryColor.substring(1, 3);
                             String g = primaryColor.substring(3, 5);
                             String b = primaryColor.substring(5, 7);
                             primaryColor = "&H" + b + g + r;
                        }

                        int marginV = (options != null && options.getMarginV() != null) ? options.getMarginV() : 20;
                        int marginL = (options != null && options.getMarginL() != null) ? options.getMarginL() : 10;
                        int alignment = (options != null && options.getAlignment() != null) ? options.getAlignment() : 2;
                        
                        String newStyle = String.format("Style: Default,%s,%d,%s,&H000000,&H000000,&H000000,0,0,0,0,100,100,0,0,1,0,0,%d,%d,%d,%d,1",
                                fontName, fontSize, primaryColor, alignment, marginL, marginL, marginV);
                        
                        writer.write(newStyle);
                    } else if (trimmed.equals("[Events]")) {
                        inEvents = true;
                        writer.write(line);
                    } else if (inEvents && line.startsWith("Dialogue:")) {
                        if (eventIndex < positions.size()) {
                            int[] xy = positions.get(eventIndex);
                            String posTag = null;
                            if (xy != null) {
                                int px = xy[0];
                                int py = xy[1];
                                int baseWidth = outputWidth > 0 ? outputWidth : 1;
                                int baseHeight = outputHeight > 0 ? outputHeight : 1;
                                int prx = playResX > 0 ? playResX : baseWidth;
                                int pry = playResY > 0 ? playResY : baseHeight;
                                double scaleX = (double) prx / (double) baseWidth;
                                double scaleY = (double) pry / (double) baseHeight;
                                int assX = (int) Math.round(px * scaleX);
                                int assY = (int) Math.round(py * scaleY);
                                if (assX < 0) assX = 0;
                                if (assY < 0) assY = 0;
                                if (assX > prx) assX = prx;
                                if (assY > pry) assY = pry;
                                posTag = "{\\pos(" + assX + "," + assY + ")}";
                            }
                            if (posTag != null) {
                                int textStart = 0;
                                for (int i = 0; i < 9; i++) {
                                    textStart = line.indexOf(',', textStart) + 1;
                                }
                                
                                if (textStart > 0) {
                                    String prefix = line.substring(0, textStart);
                                    String content = line.substring(textStart);
                                    writer.write(prefix + posTag + content);
                                } else {
                                    writer.write(line);
                                }
                            } else {
                                writer.write(line);
                            }
                            eventIndex++;
                        } else {
                            writer.write(line);
                        }
                    } else {
                        writer.write(line);
                    }
                    writer.newLine();
                }
            }
            
            // Cleanup intermediates
            Files.deleteIfExists(cleanSrtPath);
            Files.deleteIfExists(assPath);
            Files.deleteIfExists(inputPath);
            
            return finalAssPath;
            
        } catch (Exception e) {
            log.error("Failed to preprocess subtitle to ASS: {}", e.getMessage(), e);
            return inputPath; // Fallback to original
        }
    }

    private String getFullUrl(String objectName) {
        // Use externalEndpoint if configured, otherwise fallback to internal endpoint
        String endpoint = StringUtils.hasText(minioConfig.getExternalEndpoint()) 
                ? minioConfig.getExternalEndpoint() 
                : minioConfig.getEndpoint();
                
        if (!endpoint.endsWith("/")) { 
            endpoint += "/";
        }
        return endpoint + minioConfig.getBucketName() + "/" + objectName;
    }

    public String processVideo(VideoProcessRequest request) throws Exception {
        Path tempInput = downloadFile(request.getObjectName());
        String outputFormat = request.getOutputFormat() != null ? request.getOutputFormat() : "mp4";
        Path tempOutput = Files.createTempFile("output-", "." + outputFormat);
        Path tempSubtitle = null;

        try {
            // Probe input
            FFmpegProbeResult probeResult = ffprobe.probe(tempInput.toString());
            FFmpegStream videoStream = probeResult.getStreams().stream()
                    .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No video stream found in input file"));

            log.info("Input video: {}x{}, codec={}, fps={}/{}, Duration={}s",
                    videoStream.width, videoStream.height,
                    videoStream.codec_name,
                    videoStream.avg_frame_rate != null ? videoStream.avg_frame_rate.getNumerator() : "?",
                    videoStream.avg_frame_rate != null ? videoStream.avg_frame_rate.getDenominator() : "?",
                    videoStream.duration);

            // Validate crop
            if (request.getCropWidth() != null && request.getCropHeight() != null) {
                int x = request.getCropX() != null ? request.getCropX() : 0;
                int y = request.getCropY() != null ? request.getCropY() : 0;
                if (x + request.getCropWidth() > videoStream.width || y + request.getCropHeight() > videoStream.height) {
                    throw new IllegalArgumentException(String.format(
                            "Crop area (%d,%d %dx%d) exceeds video dimensions (%dx%d)",
                            x, y, request.getCropWidth(), request.getCropHeight(),
                            videoStream.width, videoStream.height));
                }
            }

            String videoCodec = request.getVideoCodec() != null ? request.getVideoCodec() : "libx264";
            boolean isCopy = "copy".equalsIgnoreCase(videoCodec);

            // --- Use input-side seeking for fast seek (only when not using subtitle filter) ---
            // Input-side seek (-ss before -i) is faster: FFmpeg skips to keyframe without decoding
            // Output-side seek (-ss after -i) is frame-accurate but decodes from start
            // If subtitles are applied we must use output-side seek for accuracy
            boolean hasSubtitle = request.getSubtitle() != null
                    && StringUtils.hasText(request.getSubtitle().getObjectName());

            // Rebuild with full output options
            FFmpegOutputBuilder outputBuilder = new FFmpegBuilder()
                    .setInput(tempInput.toString())
                    .overrideOutputFiles(true)
                    .addOutput(tempOutput.toString());

            // Output-side seek (frame-accurate, used with subtitles or copy mode)
            if (request.getStartTime() != null && (hasSubtitle || isCopy)) {
                outputBuilder.setStartOffset((long) (request.getStartTime() * 1000), TimeUnit.MILLISECONDS);
            }
            if (request.getDuration() != null) {
                outputBuilder.setDuration((long) (request.getDuration() * 1000), TimeUnit.MILLISECONDS);
            }

            // --- Video codec settings ---
            if (isCopy) {
                outputBuilder.setVideoCodec("copy");
            } else {
                outputBuilder.setVideoCodec(videoCodec);

                // Bitrate mode: CRF (quality-based) takes priority over target bitrate
                if (request.getCrf() != null) {
                    // CRF: constant quality mode, no target bitrate
                    outputBuilder.addExtraArgs("-crf", String.valueOf(request.getCrf()));
                } else if (request.getBitrate() != null) {
                    outputBuilder.setVideoBitRate(request.getBitrate() * 1000L);
                    // Set VBV buffer to 2× bitrate for smoother bitrate control
                    outputBuilder.addExtraArgs("-maxrate", request.getBitrate() + "k",
                            "-bufsize", (request.getBitrate() * 2) + "k");
                }

                // Encoding preset (speed/compression trade-off)
                String preset = request.getPreset() != null ? request.getPreset() : "medium";
                outputBuilder.addExtraArgs("-preset", preset);

                // H.264 profile and level for compatibility
                if (request.getProfile() != null) {
                    outputBuilder.addExtraArgs("-profile:v", request.getProfile());
                }
                if (request.getLevel() != null) {
                    outputBuilder.addExtraArgs("-level:v", request.getLevel());
                }

                // Pixel format: yuv420p for maximum compatibility (required for most devices/browsers)
                outputBuilder.addExtraArgs("-pix_fmt", "yuv420p");
            }

            // --- Audio codec settings ---
            String audioCodec = request.getAudioCodec() != null ? request.getAudioCodec() : "aac";
            outputBuilder.setAudioCodec(audioCodec);
            if (!"copy".equalsIgnoreCase(audioCodec)) {
                int audioBitrate = request.getAudioBitrate() != null ? request.getAudioBitrate() : 128;
                outputBuilder.setAudioBitRate(audioBitrate * 1000L);
            }

            // --- Video filters ---
            int outputWidth = request.getCropWidth() != null ? request.getCropWidth() : videoStream.width;
            int outputHeight = request.getCropHeight() != null ? request.getCropHeight() : videoStream.height;

            List<String> videoFilters = new ArrayList<>();

            // Crop filter
            if (request.getCropWidth() != null && request.getCropHeight() != null) {
                videoFilters.add(String.format("crop=%d:%d:%d:%d",
                        request.getCropWidth(), request.getCropHeight(),
                        request.getCropX() != null ? request.getCropX() : 0,
                        request.getCropY() != null ? request.getCropY() : 0));
            }

            // Scale filter (after crop, before subtitle)
            if (request.getScaleWidth() != null || request.getScaleHeight() != null) {
                int sw = request.getScaleWidth() != null ? request.getScaleWidth() : -2;
                int sh = request.getScaleHeight() != null ? request.getScaleHeight() : -2;
                // force_original_aspect_ratio=decrease ensures output fits in the box,
                // -2 aligns dimension to be divisible by 2 (required for yuv420p)
                videoFilters.add(String.format("scale=%d:%d:flags=lanczos", sw, sh));
                outputWidth = request.getScaleWidth() != null ? request.getScaleWidth() : outputWidth;
                outputHeight = request.getScaleHeight() != null ? request.getScaleHeight() : outputHeight;
            }

            // Subtitle filter (must be last in filter chain)
            if (hasSubtitle) {
                Path originalSubtitle = downloadFile(request.getSubtitle().getObjectName());
                tempSubtitle = preprocessSubtitle(originalSubtitle, request.getSubtitle(), outputWidth, outputHeight);
                String subPath = tempSubtitle.toAbsolutePath().toString()
                        .replace("\\", "/").replace(":", "\\:");
                videoFilters.add("subtitles='" + subPath + "'");
            }

            if (!videoFilters.isEmpty()) {
                outputBuilder.setVideoFilter(String.join(",", videoFilters));
            }

            FFmpegBuilder finalBuilder = outputBuilder.done();
            // Inject input-side fast seek when no subtitle is used
            if (request.getStartTime() != null && !hasSubtitle && !isCopy) {
                List<String> cmd = finalBuilder.build();
                // Insert -ss before -i flag
                List<String> patched = new ArrayList<>();
                patched.add(cmd.get(0)); // ffmpeg binary
                patched.add("-ss");
                patched.add(String.valueOf(request.getStartTime()));
                patched.addAll(cmd.subList(1, cmd.size()));
                log.info("Executing FFmpeg command (fast-seek): {}", String.join(" ", patched));
                ProcessBuilder pb = new ProcessBuilder(patched);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                StringBuilder output = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append("\n");
                }
                int code = proc.waitFor();
                if (code != 0) {
                    log.error("FFmpeg failed (code {}): {}", code, output);
                    throw new RuntimeException("FFmpeg video processing failed with exit code " + code);
                }
            } else {
                log.info("Executing FFmpeg command: {}", finalBuilder.build());
                new FFmpegExecutor(ffmpeg, ffprobe).createJob(finalBuilder).run();
            }

            return uploadFile(tempOutput, outputFormat);

        } finally {
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);
            if (tempSubtitle != null) Files.deleteIfExists(tempSubtitle);
        }
    }

    public String processImage(ImageProcessRequest request) throws Exception {
        Path tempInput = downloadFile(request.getObjectName());
        String ext = request.getOutputFormat() != null ? request.getOutputFormat() : "jpg";
        Path tempOutput = Files.createTempFile("output-", "." + ext);

        try {
            FFmpegOutputBuilder outputBuilder = new FFmpegBuilder()
                    .setInput(tempInput.toString())
                    .overrideOutputFiles(true)
                    .addOutput(tempOutput.toString())
                    .setFormat("image2"); // Force image muxer if needed, usually auto-detected by extension

            List<String> filters = new ArrayList<>();

            // Crop
            if (request.getCropWidth() != null && request.getCropHeight() != null) {
                filters.add(String.format("crop=%d:%d:%d:%d",
                        request.getCropWidth(), request.getCropHeight(),
                        request.getCropX() != null ? request.getCropX() : 0,
                        request.getCropY() != null ? request.getCropY() : 0));
            }

            // Scale
            if (request.getWidth() != null || request.getHeight() != null) {
                int w = request.getWidth() != null ? request.getWidth() : -1; // -1 keeps aspect ratio
                int h = request.getHeight() != null ? request.getHeight() : -1;
                filters.add(String.format("scale=%d:%d", w, h));
            }

            // Rotate
            if (request.getRotate() != null) {
                // rotate filter takes radians by default, or use 'deg' suffix
                filters.add(String.format("rotate=%f*PI/180", request.getRotate()));
            }

            // Custom filter
            if (StringUtils.hasText(request.getFilter())) {
                filters.add(request.getFilter());
            }

            if (!filters.isEmpty()) {
                outputBuilder.setVideoFilter(String.join(",", filters));
            }

            FFmpegBuilder builder = outputBuilder.done();

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
            executor.createJob(builder).run();

            return uploadFile(tempOutput, ext);

        } finally {
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);
        }
    }

    public String processAudio(AudioProcessRequest request) throws Exception {
        Path tempInput = downloadFile(request.getObjectName());
        String ext = request.getOutputFormat() != null ? request.getOutputFormat() : "mp3";
        Path tempOutput = Files.createTempFile("output-", "." + ext);

        try {
            FFmpegOutputBuilder outputBuilder = new FFmpegBuilder()
                    .setInput(tempInput.toString())
                    .overrideOutputFiles(true)
                    .addOutput(tempOutput.toString());

            if (request.getStartTime() != null) {
                outputBuilder.setStartOffset((long) (request.getStartTime() * 1000), TimeUnit.MILLISECONDS);
            }
            if (request.getDuration() != null) {
                outputBuilder.setDuration((long) (request.getDuration() * 1000), TimeUnit.MILLISECONDS);
            }

            if (request.getVolume() != null) {
                outputBuilder.setAudioFilter("volume=" + request.getVolume());
            }

            FFmpegBuilder builder = outputBuilder.done();

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
            executor.createJob(builder).run();

            return uploadFile(tempOutput, ext);

        } finally {
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);
        }
    }
}
