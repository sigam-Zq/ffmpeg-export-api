# Changelog: 视频编码优化 / Video Encoding Optimization

**Date / 日期**: 2026-03-30  
**Type / 类型**: Feature + Bugfix  
**Scope / 范围**: `MediaService`, `HlsService`, `VideoProcessRequest`, `HlsProcessRequest`, 单元测试

---

## 问题修复 / Bug Fixes

### 1. `HlsService` — `bufsize` 计算错误
- **问题**: 自定义分辨率场景下，`bufsize` 复用了 `bitrate` 原始值（如 `"4000k"`），而 `maxrate` 通过字符串拼接生成了 `"4800000k"` 这样的错误值。
- **修复**: 新增 `deriveMaxrate()` 和 `deriveBufsize()` 辅助方法，正确计算 `maxrate = 1.2×bitrate`、`bufsize = 2×bitrate`。

### 2. `HlsService` — GOP 硬编码导致切片对齐失败
- **问题**: `-g`/`-keyint_min` 固定为 `60`，未考虑视频真实帧率，导致 HLS 切片无法保证每段起点都是关键帧（I 帧），播放器 seek 会出现黑帧。
- **修复**: 通过 `ffprobe` 读取真实帧率（`avg_frame_rate.getNumerator() / getDenominator()`），动态计算 `gopSize = round(fps × segmentSeconds)`。

### 3. `HlsService` — `scale` filter 缺少宽高保护
- **问题**: `scale=w=W:h=H` 对非 16:9 视频会强制拉伸，非偶数输出宽高会导致 `yuv420p` 报错。
- **修复**: 改用 `scale=w=W:h=H:force_original_aspect_ratio=decrease:flags=lanczos,pad=W:H:(ow-iw)/2:(oh-ih)/2`，保持宽高比并填充黑边，同时使用 lanczos 算法提升缩放质量。

### 4. `HlsService` — 单路音频无需 `asplit`
- **问题**: 仅有 1 路输出质量等级时，仍执行 `asplit=1`，产生无谓的 filter 开销。
- **修复**: 仅当 `levels.size() > 1` 时才使用 `asplit`；单路输出直接 `-map 0:a`。

### 5. `MediaService.processVideo` — `seek` 全部置于 output 侧性能差
- **问题**: `-ss` 参数位于 `-i` 之后（output-side），FFmpeg 需要解码整个文件才能定位到目标时间点，对长视频性能极差。
- **修复**: 无字幕、非 `copy` 模式时，将 `-ss` 移至 `-i` 之前（input-side fast seek），大幅提升定位速度。有字幕时保留 output-side seek 以保证逐帧精度。

### 6. `Fraction` 私有字段访问错误（编译错误）
- **问题**: 代码直接访问 `videoStream.avg_frame_rate.numerator` / `.denominator`，这两个字段在 `org.apache.commons.lang3.math.Fraction` 中是私有的，导致编译失败。
- **修复**: 改为使用公开方法 `getNumerator()` / `getDenominator()`。

---

## 新增功能 / New Features

### `VideoProcessRequest` — 新增编码控制参数

| 参数 | 类型 | 说明 |
|---|---|---|
| `crf` | Integer | libx264/libx265 恒质量系数（0-51），与 `bitrate` 互斥，优先级更高 |
| `preset` | String | 编码速度预设（`ultrafast`→`veryslow`），默认 `medium` |
| `profile` | String | H.264 Profile（`baseline`/`main`/`high`） |
| `level` | String | H.264 Level（如 `4.0`/`4.1`） |
| `videoCodec` | String | 视频编码器（默认 `libx264`，支持 `libx265`/`copy`） |
| `audioCodec` | String | 音频编码器（默认 `aac`，支持 `mp3`/`copy`） |
| `audioBitrate` | Integer | 音频码率 kbps（默认 128） |
| `scaleWidth` | Integer | 输出缩放宽度，`-2` 表示按比例自适应 |
| `scaleHeight` | Integer | 输出缩放高度，`-2` 表示按比例自适应 |

**其他视频编码优化**：
- 默认自动追加 `-pix_fmt yuv420p`（最大设备/浏览器兼容性）
- 设置 bitrate 时自动追加 `-maxrate`（1.2×bitrate）和 `-bufsize`（2×bitrate）控制码率平滑

### `HlsProcessRequest` — 新增编码控制参数

| 参数 | 类型 | 说明 |
|---|---|---|
| `preset` | String | libx264 编码速度预设，默认 `fast`（兼顾 HLS 转码速度） |
| `audioBitrate` | Integer | 每路音频码率 kbps，默认 128 |

### `HlsProcessRequest.ResolutionNode` — 新增精细码率控制

| 参数 | 类型 | 说明 |
|---|---|---|
| `maxrate` | String | 最大码率（如 `"2400k"`），默认为 1.2× `bitrate` |
| `bufsize` | String | VBV 缓冲区大小（如 `"4000k"`），默认为 2× `bitrate` |

**其他 HLS 编码优化**：
- 每路默认追加 `-profile:v main`（H.264 Main Profile，主流设备兼容）
- 音频默认使用 `-profile:a aac_low`（AAC-LC，最大兼容性）
- `determineQualityLevels` 筛选条件改为 `width <= src_width && height <= src_height`（防止对小分辨率视频向上采样）
- 预设质量等级的 `maxrate`/`bufsize` 全部修正为正确比例

---

## 单元测试 / Unit Tests

新增测试文件: `src/test/java/com/example/videoprocess/VideoEncodingTest.java`

| 测试类 | 用例数 | 覆盖内容 |
|---|---|---|
| `VideoProcessRequestTest` | 8 | 字段默认值、CRF 范围、copy 检测、preset 枚举、scale -2、crop 边界校验 |
| `HlsQualityHelperTest` | 10 | 7 种分辨率码率映射、maxrate 1.2×、bufsize 2×、异常输入兜底 |
| `HlsGopTest` | 8 | 25/30/29.97/23.976/60fps × 多种 segment 时长的 GOP 计算 |
| `HlsSegmentDurationTest` | 5 | 默认值、segmentDuration、segmentCount 优先级、极端值兜底 |
| `ResolutionNodeTest` | 2 | 独立字段设置、null 默认值 |
| **合计** | **33** | **全部通过** |

---

## 涉及文件 / Changed Files

```
src/main/java/com/example/videoprocess/
  dto/
    VideoProcessRequest.java     ← 新增 9 个编码控制字段
    HlsProcessRequest.java       ← 新增 preset/audioBitrate 字段；ResolutionNode 新增 maxrate/bufsize
  service/
    MediaService.java            ← 重写 processVideo 编码逻辑；fast seek；scale filter
    HlsService.java              ← 动态 GOP；修复 bufsize；scale 质量优化；preset 支持

src/test/java/com/example/videoprocess/
  VideoEncodingTest.java         ← 新增，33 个单元测试
```
