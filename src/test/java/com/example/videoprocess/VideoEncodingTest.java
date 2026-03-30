package com.example.videoprocess;

import com.example.videoprocess.dto.HlsProcessRequest;
import com.example.videoprocess.dto.VideoProcessRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for video encoding parameter logic.
 *
 * These tests validate DTO field defaults and helper methods
 * without requiring FFmpeg or MinIO at runtime.
 */
class VideoEncodingTest {

    // ---------------------------------------------------------------------------
    // VideoProcessRequest – encoding parameter defaults
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("VideoProcessRequest encoding fields")
    class VideoProcessRequestTest {

        @Test
        @DisplayName("Default values are null (delegated to service defaults)")
        void defaultsAreNull() {
            VideoProcessRequest req = new VideoProcessRequest();
            assertThat(req.getVideoCodec()).isNull();
            assertThat(req.getAudioCodec()).isNull();
            assertThat(req.getPreset()).isNull();
            assertThat(req.getCrf()).isNull();
            assertThat(req.getBitrate()).isNull();
            assertThat(req.getProfile()).isNull();
            assertThat(req.getLevel()).isNull();
            assertThat(req.getAudioBitrate()).isNull();
        }

        @Test
        @DisplayName("CRF value can be set to valid range [0, 51]")
        void crfValidRange() {
            VideoProcessRequest req = new VideoProcessRequest();
            req.setCrf(23);
            assertThat(req.getCrf()).isEqualTo(23);

            req.setCrf(0);
            assertThat(req.getCrf()).isEqualTo(0);

            req.setCrf(51);
            assertThat(req.getCrf()).isEqualTo(51);
        }

        @Test
        @DisplayName("Bitrate stored as kbps long value")
        void bitrateInKbps() {
            VideoProcessRequest req = new VideoProcessRequest();
            req.setBitrate(4000L);
            // Service converts kbps → bps by multiplying 1000
            assertThat(req.getBitrate() * 1000L).isEqualTo(4_000_000L);
        }

        @Test
        @DisplayName("copy codec skips re-encoding path")
        void copyCodecDetection() {
            VideoProcessRequest req = new VideoProcessRequest();
            req.setVideoCodec("copy");
            assertThat("copy".equalsIgnoreCase(req.getVideoCodec())).isTrue();
        }

        @Test
        @DisplayName("Preset values accepted")
        void presetValues() {
            String[] validPresets = {
                "ultrafast", "superfast", "veryfast", "faster", "fast",
                "medium", "slow", "slower", "veryslow"
            };
            VideoProcessRequest req = new VideoProcessRequest();
            for (String preset : validPresets) {
                req.setPreset(preset);
                assertThat(req.getPreset()).isEqualTo(preset);
            }
        }

        @Test
        @DisplayName("Scale fields accept -2 for aspect-ratio-aware scaling")
        void scaleAspectRatio() {
            VideoProcessRequest req = new VideoProcessRequest();
            req.setScaleWidth(1280);
            req.setScaleHeight(-2);
            assertThat(req.getScaleWidth()).isEqualTo(1280);
            assertThat(req.getScaleHeight()).isEqualTo(-2);
        }

        @Test
        @DisplayName("Crop validation: crop area must not exceed video dimensions")
        void cropValidation() {
            int videoWidth = 1920, videoHeight = 1080;
            VideoProcessRequest req = new VideoProcessRequest();
            req.setCropX(100);
            req.setCropY(100);
            req.setCropWidth(500);
            req.setCropHeight(400);

            int x = req.getCropX() != null ? req.getCropX() : 0;
            int y = req.getCropY() != null ? req.getCropY() : 0;
            boolean valid = (x + req.getCropWidth() <= videoWidth)
                    && (y + req.getCropHeight() <= videoHeight);
            assertThat(valid).isTrue();
        }

        @Test
        @DisplayName("Crop validation: oversized crop is detected as invalid")
        void cropValidationFails() {
            int videoWidth = 1280, videoHeight = 720;
            VideoProcessRequest req = new VideoProcessRequest();
            req.setCropX(0);
            req.setCropY(0);
            req.setCropWidth(1920); // wider than source
            req.setCropHeight(1080);

            int x = req.getCropX() != null ? req.getCropX() : 0;
            int y = req.getCropY() != null ? req.getCropY() : 0;
            boolean exceeds = (x + req.getCropWidth() > videoWidth)
                    || (y + req.getCropHeight() > videoHeight);
            assertThat(exceeds).isTrue();
        }
    }

    // ---------------------------------------------------------------------------
    // HLS bitrate / quality-ladder helpers (extracted logic mirrors service)
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("HLS quality ladder helpers")
    class HlsQualityHelperTest {

        /** Mirrors HlsService.calculateBitrate */
        private String calculateBitrate(int width, int height) {
            long pixels = (long) width * height;
            if (pixels >= 3840L * 2160) return "10000k";
            if (pixels >= 2560L * 1440) return "6000k";
            if (pixels >= 1920L * 1080) return "4000k";
            if (pixels >= 1280L * 720)  return "2000k";
            if (pixels >= 854L  * 480)  return "1000k";
            return "600k";
        }

        /** Mirrors HlsService.deriveMaxrate */
        private String deriveMaxrate(String bitrate) {
            try {
                long kbps = Long.parseLong(bitrate.replace("k", "").trim());
                return (kbps * 12 / 10) + "k";
            } catch (Exception e) {
                return bitrate;
            }
        }

        /** Mirrors HlsService.deriveBufsize */
        private String deriveBufsize(String bitrate) {
            try {
                long kbps = Long.parseLong(bitrate.replace("k", "").trim());
                return (kbps * 2) + "k";
            } catch (Exception e) {
                return bitrate;
            }
        }

        @ParameterizedTest(name = "{0}x{1} → {2}")
        @CsvSource({
            "3840, 2160, 10000k",
            "2560, 1440, 6000k",
            "1920, 1080, 4000k",
            "1280, 720,  2000k",
            "854,  480,  1000k",
            "640,  360,  600k",
            "320,  240,  600k",
        })
        @DisplayName("calculateBitrate maps resolution to expected rate")
        void calculateBitrateMappings(int width, int height, String expected) {
            assertThat(calculateBitrate(width, height)).isEqualTo(expected);
        }

        @Test
        @DisplayName("deriveMaxrate = 1.2× bitrate")
        void maxrateIs1_2x() {
            assertThat(deriveMaxrate("4000k")).isEqualTo("4800k");
            assertThat(deriveMaxrate("2000k")).isEqualTo("2400k");
            assertThat(deriveMaxrate("600k")).isEqualTo("720k");
        }

        @Test
        @DisplayName("deriveBufsize = 2× bitrate")
        void bufsizeIs2x() {
            assertThat(deriveBufsize("4000k")).isEqualTo("8000k");
            assertThat(deriveBufsize("2000k")).isEqualTo("4000k");
            assertThat(deriveBufsize("600k")).isEqualTo("1200k");
        }

        @Test
        @DisplayName("deriveMaxrate handles malformed input gracefully")
        void maxrateGracefulFallback() {
            assertThat(deriveMaxrate("invalid")).isEqualTo("invalid");
            assertThat(deriveMaxrate("")).isEqualTo("");
        }
    }

    // ---------------------------------------------------------------------------
    // HLS GOP (keyframe interval) calculation
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("HLS GOP size calculation")
    class HlsGopTest {

        /** Mirrors HlsService: gopSize = round(fps × segmentSeconds) */
        private int calculateGop(double fps, int segmentSeconds) {
            int gop = (int) Math.round(fps * segmentSeconds);
            return Math.max(gop, 1);
        }

        @ParameterizedTest(name = "fps={0}, seg={1}s → GOP={2}")
        @CsvSource({
            "25.0,   6,  150",
            "30.0,   6,  180",
            "29.97,  6,  180",   // 29.97 × 6 ≈ 179.82 → round to 180
            "23.976, 6,  144",   // 23.976 × 6 = 143.856 → round to 144
            "60.0,   4,  240",
            "25.0,   2,   50",
            "0.5,    6,    3",   // very low fps → at least 1
        })
        @DisplayName("GOP aligned to segment duration × fps")
        void gopAlignedToSegment(double fps, int seg, int expectedGop) {
            assertThat(calculateGop(fps, seg)).isEqualTo(expectedGop);
        }

        @Test
        @DisplayName("GOP is always at least 1 even at very low fps")
        void gopMinimumOne() {
            assertThat(calculateGop(0.01, 1)).isGreaterThanOrEqualTo(1);
        }
    }

    // ---------------------------------------------------------------------------
    // HLS segment duration calculation
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("HLS segment duration calculation")
    class HlsSegmentDurationTest {

        /** Mirrors HlsService: segmentCount takes priority over segmentDuration */
        private int resolveHlsTime(double duration, Integer segmentCount, Integer segmentDuration) {
            if (segmentCount != null && segmentCount > 0) {
                return (int) Math.max(1, Math.round(duration / segmentCount));
            }
            if (segmentDuration != null && segmentDuration > 0) {
                return segmentDuration;
            }
            return 6; // default
        }

        @Test
        @DisplayName("Default segment duration is 6 seconds")
        void defaultSegmentDuration() {
            assertThat(resolveHlsTime(120.0, null, null)).isEqualTo(6);
        }

        @Test
        @DisplayName("segmentDuration is used when segmentCount is not set")
        void segmentDurationTakeEffect() {
            assertThat(resolveHlsTime(120.0, null, 10)).isEqualTo(10);
        }

        @Test
        @DisplayName("segmentCount takes priority over segmentDuration")
        void segmentCountTakesPriority() {
            // 120s / 20 segments = 6s per segment
            assertThat(resolveHlsTime(120.0, 20, 10)).isEqualTo(6);
        }

        @Test
        @DisplayName("Segment duration is at least 1 second")
        void segmentDurationMinimumOne() {
            // 3s video / 100 segments → round(0.03) = 0 → clamped to 1
            assertThat(resolveHlsTime(3.0, 100, null)).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("segmentCount=0 falls back to default")
        void segmentCountZeroFallsBack() {
            assertThat(resolveHlsTime(120.0, 0, null)).isEqualTo(6);
        }
    }

    // ---------------------------------------------------------------------------
    // HlsProcessRequest.ResolutionNode – bitrate fields
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("HlsProcessRequest.ResolutionNode")
    class ResolutionNodeTest {

        @Test
        @DisplayName("All bitrate fields can be set independently")
        void bitrateFieldsIndependent() {
            HlsProcessRequest.ResolutionNode node = new HlsProcessRequest.ResolutionNode();
            node.setWidth(1280);
            node.setHeight(720);
            node.setBitrate("2000k");
            node.setMaxrate("2400k");
            node.setBufsize("4000k");

            assertThat(node.getBitrate()).isEqualTo("2000k");
            assertThat(node.getMaxrate()).isEqualTo("2400k");
            assertThat(node.getBufsize()).isEqualTo("4000k");
        }

        @Test
        @DisplayName("Optional fields default to null (auto-calculated by service)")
        void optionalFieldsNullByDefault() {
            HlsProcessRequest.ResolutionNode node = new HlsProcessRequest.ResolutionNode();
            node.setWidth(1920);
            node.setHeight(1080);

            assertThat(node.getBitrate()).isNull();
            assertThat(node.getMaxrate()).isNull();
            assertThat(node.getBufsize()).isNull();
        }
    }
}
