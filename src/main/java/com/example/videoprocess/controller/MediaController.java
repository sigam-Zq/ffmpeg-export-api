package com.example.videoprocess.controller;

import com.example.videoprocess.dto.AudioProcessRequest;
import com.example.videoprocess.dto.ImageProcessRequest;
import com.example.videoprocess.dto.VideoProcessRequest;
import com.example.videoprocess.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @PostMapping("/video/process")
    public ResponseEntity<?> processVideo(@RequestBody VideoProcessRequest request) {
        try {
            String result = mediaService.processVideo(request);
            return ResponseEntity.ok(Map.of("message", "Video processed successfully", "outputObject", result));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/image/process")
    public ResponseEntity<?> processImage(@RequestBody ImageProcessRequest request) {
        try {
            String result = mediaService.processImage(request);
            return ResponseEntity.ok(Map.of("message", "Image processed successfully", "outputObject", result));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/audio/process")
    public ResponseEntity<?> processAudio(@RequestBody AudioProcessRequest request) {
        try {
            String result = mediaService.processAudio(request);
            return ResponseEntity.ok(Map.of("message", "Audio processed successfully", "outputObject", result));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
