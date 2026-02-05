package com.example.videoprocess.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AudioProcessRequest extends BaseProcessRequest {
    private Double startTime;
    private Double duration;
    private Double volume; // 1.0 = original, 0.5 = half, 2.0 = double
}
