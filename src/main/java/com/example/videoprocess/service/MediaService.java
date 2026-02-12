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

    /**
     * Preprocesses SRT subtitle files to support non-standard "X:123 Y:456" coordinates in timestamp lines.
     * Converts the entire file to ASS format to ensure proper positioning and styling.
     */
    private Path preprocessSubtitle(Path inputPath, com.example.videoprocess.dto.SubtitleOptions options) {
        try {
            // 1. Scan and extract X:Y coordinates, create a clean SRT without them
            Path cleanSrtPath = Files.createTempFile("clean-", ".srt");
            List<String> positions = new ArrayList<>();
            
            // Regex to find X:123 Y:456 format
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(.*-->.*)\\s+X:(\\d+)\\s+Y:(\\d+).*");
            
            try (java.io.BufferedReader reader = Files.newBufferedReader(inputPath);
                 java.io.BufferedWriter writer = Files.newBufferedWriter(cleanSrtPath)) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    java.util.regex.Matcher matcher = pattern.matcher(line);
                    if (matcher.matches()) {
                        String cleanTimestamp = matcher.group(1).trim();
                        String x = matcher.group(2);
                        String y = matcher.group(3);
                        
                        writer.write(cleanTimestamp);
                        writer.newLine();
                        
                        // Store the pos tag for this subtitle block
                        positions.add("{\\pos(" + x + "," + y + ")}");
                    } else if (line.contains("-->")) {
                         // Normal timestamp line without X:Y, add placeholder (null) to keep index sync
                         writer.write(line);
                         writer.newLine();
                         positions.add(null);
                    } else {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
            
            // 2. Convert clean SRT to ASS using FFmpeg
            // This handles HTML tags (<font>, etc.) conversion to ASS tags automatically
            Path assPath = Files.createTempFile("converted-", ".ass");
            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(cleanSrtPath.toString())
                    .overrideOutputFiles(true)
                    .addOutput(assPath.toString())
                    .setFormat("ass")
                    .done();
            
            new FFmpegExecutor(ffmpeg, ffprobe).createJob(builder).run();
            
            // 3. Inject styles and positions into the ASS file
            Path finalAssPath = Files.createTempFile("final-", ".ass");
            try (java.io.BufferedReader reader = Files.newBufferedReader(assPath);
                 java.io.BufferedWriter writer = Files.newBufferedWriter(finalAssPath)) {
                
                String line;
                int eventIndex = 0;
                boolean inEvents = false;
                
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("Style: Default")) {
                        // Replace Default style with user options
                        // ASS Style: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
                        // Default: Style: Default,Arial,16,&Hffffff,&Hffffff,&H0,&H0,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,0
                        
                        String fontName = "Noto Sans CJK SC";
                        int fontSize = (options != null && options.getFontSize() != null) ? options.getFontSize() : 24;
                        String primaryColor = (options != null && StringUtils.hasText(options.getFontColor())) ? options.getFontColor() : "&HFFFFFF";
                        // Ensure color is in &H format if not already
                        if (primaryColor.startsWith("#")) {
                             // Convert #RRGGBB to &HBBGGRR (ASS uses BGR)
                             // Simple assumption: input is #RRGGBB
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
                    } else if (line.trim().equals("[Events]")) {
                        inEvents = true;
                        writer.write(line);
                    } else if (inEvents && line.startsWith("Dialogue:")) {
                        // Format: Dialogue: 0,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
                        // We need to inject {\pos(x,y)} at the start of Text
                        
                        if (eventIndex < positions.size()) {
                            String posTag = positions.get(eventIndex);
                            if (posTag != null) {
                                // Find the 9th comma to locate Text start
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
        Path tempOutput = Files.createTempFile("output-", "." + (request.getOutputFormat() != null ? request.getOutputFormat() : "mp4"));
        Path tempSubtitle = null;

        try {
            // Probe input
            FFmpegProbeResult probeResult = ffprobe.probe(tempInput.toString());
            FFmpegStream videoStream = probeResult.getStreams().stream()
                    .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No video stream found in input file"));

            log.info("Input video: {}x{}, Duration: {}s", videoStream.width, videoStream.height, videoStream.duration);

            // Validate crop
            if (request.getCropWidth() != null && request.getCropHeight() != null) {
                int x = request.getCropX() != null ? request.getCropX() : 0;
                int y = request.getCropY() != null ? request.getCropY() : 0;
                if (x + request.getCropWidth() > videoStream.width || y + request.getCropHeight() > videoStream.height) {
                    throw new IllegalArgumentException(String.format(
                        "Crop area (%d,%d %dx%d) exceeds video dimensions (%dx%d)", 
                        x, y, request.getCropWidth(), request.getCropHeight(), videoStream.width, videoStream.height));
                }
            }

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

            if (request.getBitrate() != null) {
                // Convert kbps to bps
                outputBuilder.setVideoBitRate(request.getBitrate() * 1000);
            }

            // Build video filters
            List<String> videoFilters = new ArrayList<>();
            if (request.getCropWidth() != null && request.getCropHeight() != null) {
                String crop = String.format("crop=%d:%d:%d:%d", 
                        request.getCropWidth(), request.getCropHeight(),
                        request.getCropX() != null ? request.getCropX() : 0,
                        request.getCropY() != null ? request.getCropY() : 0);
                videoFilters.add(crop);
            }

            if (request.getSubtitle() != null && StringUtils.hasText(request.getSubtitle().getObjectName())) {
                Path originalSubtitle = downloadFile(request.getSubtitle().getObjectName());
                // Preprocess subtitle to handle custom X:Y positioning and convert to ASS
                tempSubtitle = preprocessSubtitle(originalSubtitle, request.getSubtitle());
                
                // Note: Windows path escaping might be tricky for FFmpeg subtitles filter.
                // Usually forward slashes work best in FFmpeg even on Windows.
                String subPath = tempSubtitle.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:");
                
                // Use the ASS file directly. No force_style needed as styles are embedded in ASS header.
                StringBuilder subFilter = new StringBuilder("subtitles='").append(subPath).append("'");
                videoFilters.add(subFilter.toString());
            }

            if (!videoFilters.isEmpty()) {
                outputBuilder.setVideoFilter(String.join(",", videoFilters));
            }

            FFmpegBuilder builder = outputBuilder.done();

            log.info("Executing FFmpeg command: {}", builder.build());
            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
            executor.createJob(builder).run();

            return uploadFile(tempOutput, request.getOutputFormat() != null ? request.getOutputFormat() : "mp4");

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
