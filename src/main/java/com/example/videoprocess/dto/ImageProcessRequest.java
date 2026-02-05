package com.example.videoprocess.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ImageProcessRequest extends BaseProcessRequest {
    // Crop
    private Integer cropX;
    private Integer cropY;
    private Integer cropWidth;
    private Integer cropHeight;
    
    // Scale
    private Integer width;
    private Integer height;
    
    // Rotate
    private Double rotate; // degrees (90, 180, 270)
    
    // Filter
    private String filter; // Custom FFmpeg filter string
}
