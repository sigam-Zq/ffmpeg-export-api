package com.example.videoprocess.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HlsProcessResponse {
    /**
     * The path to the master.m3u8 file in MinIO.
     * master.m3u8 文件的 MinIO 路径。
     */
    private String masterM3u8Path;

    /**
     * The full URL to the master.m3u8 file.
     * master.m3u8 文件的完整访问 URL。
     */
    private String masterM3u8Url;

    /**
     * List of generated HLS streams.
     * 生成的 HLS 流列表。
     */
    private List<StreamInfo> streams;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamInfo {
        /**
         * The resolution of the stream (e.g., "1920x1080").
         * 流的分辨率。
         */
        private String resolution;

        /**
         * The path to the index.m3u8 file for this stream in MinIO.
         * 该流在 MinIO 中 index.m3u8 文件的路径。
         */
        private String m3u8Path;
        
        /**
         * The full URL to the index.m3u8 file.
         * index.m3u8 文件的完整访问 URL。
         */
        private String url;
    }
}
