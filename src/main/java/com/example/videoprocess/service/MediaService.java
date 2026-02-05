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

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${ffmpeg.ffprobe-path:ffprobe}")
    private String ffprobePath;

    private FFmpeg ffmpeg;
    private FFprobe ffprobe;

    @PostConstruct
    public void init() {
        try {
            ffmpeg = new FFmpeg(ffmpegPath);
            ffprobe = new FFprobe(ffprobePath);
        } catch (Exception e) {
            log.error("Failed to initialize FFmpeg/FFprobe: {}", e.getMessage());
        }
    }

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
        return outputObjectName;
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

            if (StringUtils.hasText(request.getSubtitleObject())) {
                tempSubtitle = downloadFile(request.getSubtitleObject());
                // Note: Windows path escaping might be tricky for FFmpeg subtitles filter.
                // Usually forward slashes work best in FFmpeg even on Windows.
                String subPath = tempSubtitle.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:");
                videoFilters.add("subtitles='" + subPath + "'");
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
