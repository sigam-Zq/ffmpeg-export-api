package com.example.videoprocess;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class VideoProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoProcessApplication.class, args);
    }


    //  @Bean
    // public CommandLineRunner testConfig(Environment env) {
    //     return args -> {
    //         System.out.println("=== 配置文件检查 ===");
    //         System.out.println("ffmpeg.path: " + env.getProperty("ffmpeg.path"));
    //         System.out.println("ffmpeg.ffprobe-path: " + env.getProperty("ffmpeg.ffprobe-path"));
    //         System.out.println("所有 ffmpeg 相关配置:");
    //         for (String key : List.of("ffmpeg.path", "ffmpeg.ffprobe-path")) {
    //             System.out.println(key + " = " + env.getProperty(key));
    //         }
    //     };
    // }

}
