package com.example.videoprocess.dto;

import lombok.Data;

@Data
public class HlsProcessRequest {
    /**
     * The relative path of the input video file in MinIO.
     * 视频文件在 MinIO 中的相对地址。
     */
    private String objectName;

    /**
     * The target path for storing the processed HLS files in MinIO.
     * 处理后 HLS 文件在 MinIO 中的存储路径。
     */
    private String targetPath;
}
