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

    private SubtitleOptions subtitle; // Unified subtitle options

    /** Target video bitrate in kbps. Mutually exclusive with crf. */
    private Long bitrate; // kbps

    /**
     * libx264/libx265 CRF value (0-51, lower = better quality).
     * Recommended: 18-28 for libx264. If set, bitrate is ignored for quality control.
     */
    private Integer crf;

    /**
     * Encoding speed preset: ultrafast / superfast / veryfast / faster / fast /
     * medium(default) / slow / slower / veryslow.
     * Slower preset = better compression at same quality, but more CPU time.
     */
    private String preset; // e.g. "medium", "fast", "slow"

    /**
     * H.264 profile: baseline / main / high.
     * Use "baseline" for maximum device compatibility (no B-frames).
     */
    private String profile; // e.g. "high", "main", "baseline"

    /**
     * H.264 level, e.g. "4.0", "4.1", "5.0".
     */
    private String level; // e.g. "4.0"

    /**
     * Video codec to use, e.g. "libx264" (default), "libx265", "copy".
     * Use "copy" to skip re-encoding when only cutting/container change is needed.
     */
    private String videoCodec;

    /**
     * Audio codec, e.g. "aac" (default), "mp3", "copy".
     */
    private String audioCodec;

    /**
     * Audio bitrate in kbps, e.g. 128 (default), 192, 256.
     */
    private Integer audioBitrate;

    /**
     * Output scale width. Set to -2 to keep aspect ratio (height must also be set or vice versa).
     */
    private Integer scaleWidth;

    /**
     * Output scale height. Set to -2 to keep aspect ratio.
     */
    private Integer scaleHeight;
}
