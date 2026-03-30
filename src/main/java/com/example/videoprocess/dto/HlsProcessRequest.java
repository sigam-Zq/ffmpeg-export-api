package com.example.videoprocess.dto;

import java.util.List;

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

    /**
     * List of custom resolutions to generate.
     * 自定义分辨率列表。
     */
    private List<ResolutionNode> resolutions;

    /**
     * Custom segment duration in seconds (hls_time). Default: 6.
     * 自定义切片时长（秒）。
     */
    private Integer segmentDuration;

    /**
     * Custom number of segments (overrides segmentDuration if set).
     * 自定义切片个数（如果设置，将覆盖 segmentDuration）。
     */
    private Integer segmentCount;

    /**
     * libx264 encoding speed preset: ultrafast/superfast/veryfast/faster/fast/medium/slow/slower/veryslow.
     * Default: "fast" for HLS to balance speed and compression.
     * libx264 编码速度预设，默认 "fast"。
     */
    private String preset;

    /**
     * Audio bitrate per stream in kbps. Default: 128.
     * 每路音频码率（kbps），默认 128。
     */
    private Integer audioBitrate;

    @Data
    public static class ResolutionNode {
        private Integer width;
        private Integer height;
        /** Target video bitrate, e.g. "2000k". If omitted, auto-calculated. */
        private String bitrate;
        /** Max bitrate, e.g. "2400k". Defaults to 1.2× bitrate. */
        private String maxrate;
        /** VBV buffer size, e.g. "4000k". Defaults to 2× bitrate. */
        private String bufsize;
    }
}
