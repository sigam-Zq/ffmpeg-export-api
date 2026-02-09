package com.example.videoprocess.dto;

import lombok.Data;

@Data
public class SubtitleOptions {
    private String objectName; // MinIO object name for SRT file
    private Integer fontSize; // Font size (e.g., 24)
    private String fontColor; // Font color (e.g., &HFFFFFF)
    private Integer marginV; // Vertical margin (for Y positioning)
    private Integer marginL; // Left margin (for X positioning)
    private Integer alignment; // Alignment (2=Bottom Center, 1=Bottom Left, etc.)
}
