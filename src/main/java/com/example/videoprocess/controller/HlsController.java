package com.example.videoprocess.controller;

import com.example.videoprocess.dto.HlsProcessRequest;
import com.example.videoprocess.dto.HlsProcessResponse;
import com.example.videoprocess.service.HlsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/media/hls")
@RequiredArgsConstructor
public class HlsController {

    private final HlsService hlsService;

    @PostMapping("/process")
    public Map<String, Object> processHls(@RequestBody HlsProcessRequest request) {
        try {
            HlsProcessResponse response = hlsService.processHls(request);
            
            Map<String, Object> result = new HashMap<>();
            result.put("message", "HLS processing completed");
            result.put("streams", response.getStreams());
            
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("HLS processing failed: " + e.getMessage());
        }
    }
}
