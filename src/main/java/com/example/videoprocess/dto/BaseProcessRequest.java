package com.example.videoprocess.dto;

import lombok.Data;

@Data
public class BaseProcessRequest {
    private String objectName;
    private String outputFormat; // e.g., "mp4", "jpg", "mp3"
}
