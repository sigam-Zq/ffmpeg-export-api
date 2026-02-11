package com.example.videoprocess.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.Iterator;

@Slf4j
@Configuration
public class ConfigLogger {

    private final Environment env;

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${ffmpeg.ffprobe-path:ffprobe}")
    private String ffprobePath;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.bucket-name}")
    private String minioBucketName;

    public ConfigLogger(Environment env) {
        this.env = env;
    }

    @PostConstruct
    public void logConfig() {
        log.info("=================================================");
        log.info("           Active Configuration Info             ");
        log.info("=================================================");
        log.info("Active Profiles: {}", Arrays.toString(env.getActiveProfiles()));
        
        // Print loaded property sources to identify which config files are read
        if (env instanceof AbstractEnvironment) {
            log.info("Loaded Property Sources:");
            for (PropertySource<?> source : ((AbstractEnvironment) env).getPropertySources()) {
                // Filter out system properties and env vars to reduce noise if needed, 
                // but usually seeing 'application.yaml' or 'Config resource' is what is wanted.
                if (source.getName().contains("Config resource") || 
                    source.getName().contains("application") ||
                    source.getName().endsWith(".properties") || 
                    source.getName().endsWith(".yml") ||
                    source.getName().endsWith(".yaml")) {
                    log.info(" - {}", source.getName());
                }
            }
        }

        log.info("-------------------------------------------------");
        log.info("Key Property Values:");
        log.info("FFmpeg Path: {}", ffmpegPath);
        log.info("FFprobe Path: {}", ffprobePath);
        log.info("MinIO Endpoint: {}", minioEndpoint);
        log.info("MinIO Bucket: {}", minioBucketName);
        log.info("=================================================");
    }
}
