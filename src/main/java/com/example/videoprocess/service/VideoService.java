package com.example.videoprocess.service;

import com.example.videoprocess.config.MinioConfig;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

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

    public String clipVideo(String objectName, double startTime, double duration) throws Exception {
        Path tempInput = Files.createTempFile("input-", ".mp4");
        Path tempOutput = Files.createTempFile("output-", ".mp4");

        try {
            // 1. Download video from MinIO
            log.info("Downloading {} from MinIO bucket {}", objectName, minioConfig.getBucketName());
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectName)
                            .build())) {
                Files.copy(stream, tempInput, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. Process with FFmpeg
            log.info("Clipping video: start={}, duration={}", startTime, duration);
            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(tempInput.toString())
                    .overrideOutputFiles(true)
                    .addOutput(tempOutput.toString())
                    .setStartOffset((long) (startTime * 1000), TimeUnit.MILLISECONDS)
                    .setDuration((long) (duration * 1000), TimeUnit.MILLISECONDS)
                    .setFormat("mp4")
                    .done();

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);
            executor.createJob(builder).run();

            // 3. Upload result to MinIO
            String outputObjectName = "clipped-" + UUID.randomUUID() + ".mp4";
            log.info("Uploading result to MinIO as {}", outputObjectName);
            
            try (InputStream uploadStream = new FileInputStream(tempOutput.toFile())) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(minioConfig.getBucketName())
                                .object(outputObjectName)
                                .stream(uploadStream, tempOutput.toFile().length(), -1)
                                .contentType("video/mp4")
                                .build());
            }

            return outputObjectName;

        } finally {
            // Cleanup
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);
        }
    }
}
