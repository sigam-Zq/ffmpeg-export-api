package com.example.videoprocess.config;

import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFprobe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;

@Slf4j
@Configuration
public class FFmpegConfig {

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${ffmpeg.ffprobe-path:ffprobe}")
    private String ffprobePath;

    @Bean
    public FFmpeg ffmpeg() {
        try {
            FFmpeg ffmpeg = new FFmpeg(ffmpegPath);
            log.info("FFmpeg initialized with path: {}", ffmpegPath);
            return ffmpeg;
        } catch (IOException e) {
            log.error("Failed to initialize FFmpeg", e);
            throw new RuntimeException("Failed to initialize FFmpeg", e);
        }
    }

    @Bean
    public FFprobe ffprobe() {
        try {
            FFprobe ffprobe = new FFprobe(ffprobePath);
            log.info("FFprobe initialized with path: {}", ffprobePath);
            return ffprobe;
        } catch (IOException e) {
            log.error("Failed to initialize FFprobe", e);
            throw new RuntimeException("Failed to initialize FFprobe", e);
        }
    }
}
