package com.example.videoprocess.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioInitializer implements CommandLineRunner {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @Override
    public void run(String... args) throws Exception {
        try {
            String bucketName = minioConfig.getBucketName();
            log.info("Connecting to MinIO at {}...", minioConfig.getEndpoint());
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                log.info("Bucket '{}' not found, creating it...", bucketName);
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Bucket '{}' created successfully.", bucketName);
            } else {
                log.info("Bucket '{}' already exists.", bucketName);
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO: {}", e.getMessage(), e);
            throw e;
        }
    }
}
