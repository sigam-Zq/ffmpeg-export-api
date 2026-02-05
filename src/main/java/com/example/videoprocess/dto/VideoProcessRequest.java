package com.example.videoprocess.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class VideoProcessRequest extends BaseProcessRequest {
    private Double startTime; // seconds
    private Double duration; // seconds
    
    // Crop parameters
    private Integer cropX;
    private Integer cropY;
    private Integer cropWidth;
    private Integer cropHeight;
    
    private String subtitleObject; // MinIO object name for SRT file
}
