package com.example.videoprocess.controller;

import com.example.videoprocess.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    @PostMapping("/clip")
    public ResponseEntity<?> clipVideo(@RequestParam String objectName,
                                       @RequestParam double startTime,
                                       @RequestParam double duration) {
        try {
            String result = videoService.clipVideo(objectName, startTime, duration);
            return ResponseEntity.ok(Map.of("message", "Video clipped successfully", "outputObject", result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
