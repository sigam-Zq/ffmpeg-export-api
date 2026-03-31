# FFmpeg / FFprobe 使用分析文档

---

## 一、两个工具的角色定位

```
ffprobe  → 「侦察兵」：只读，探测视频元数据（分辨率、帧率、时长、编码格式等）
ffmpeg   → 「工程师」：读写，执行实际的编解码、转码、裁切、滤镜操作
```

项目里使用两种调用方式：
- **Java API 封装**（`net.bramp.ffmpeg`）：`FFmpegBuilder` + `FFmpegExecutor` → 自动拼接命令行
- **直接 ProcessBuilder 调用**：手动拼 `List<String>` 命令，适合需要精细控制参数顺序的场景

---

## 二、按接口维度逐一解析

### 接口一：`POST /media/video/process` — 视频处理

#### 第一步：ffprobe 探测

```java
FFmpegProbeResult probeResult = ffprobe.probe(tempInput.toString());
FFmpegStream videoStream = probeResult.getStreams().stream()
        .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
        .findFirst()...
```

等价命令行：
```bash
ffprobe -v quiet -print_format json -show_streams -show_format input.mp4
```

读取到的关键信息：`width/height`（用于校验 crop 越界）、`avg_frame_rate`（分数形式帧率）。

---

#### 第二步：ffmpeg 编码（双模式 seek 策略）

| 场景 | seek 位置 | 命令行形式 | 特点 |
|---|---|---|---|
| **有字幕 / copy 模式** | output 侧（`-ss` 在 `-i` 后面） | `ffmpeg -i in.mp4 -ss 10 ...` | 逐帧精确，但解码慢 |
| **普通转码（无字幕）** | input 侧（`-ss` 在 `-i` 前面） | `ffmpeg -ss 10 -i in.mp4 ...` | 跳到关键帧，非常快 |

**三种互斥编码模式**：

| 编码模式 | 参数 | 等价命令行 | 说明 |
|---|---|---|---|
| **copy 模式** | `videoCodec=copy` | `-c:v copy` | 不重新编码，只改容器，最快 |
| **CRF 恒质量** | `crf=23` | `-crf 23` | 质量固定，码率浮动，推荐日常使用 |
| **目标码率 VBV** | `bitrate=2000` | `-b:v 2000k -maxrate 2000k -bufsize 4000k` | 码率受控，bufsize=2×bitrate |

**滤镜链顺序约束**（`crop → scale → subtitles`）：

```bash
-vf "crop=640:360:100:50, scale=1280:720:flags=lanczos, subtitles='/tmp/final-.ass'"
```

> `-2` 的含义：scale 中 `-2` 表示「自动计算该维度，但保证结果是 2 的倍数」（yuv420p 要求宽高为偶数）。

---

#### 字幕预处理（两阶段）

```bash
# 第一步：SRT → ASS 格式转换
ffmpeg -i clean.srt -f ass output.ass
```

然后 Java 代码手动编辑 ASS 文件（修改字体、颜色、位置标签 `{\pos(x,y)}`），最终通过 `subtitles=` 滤镜烧录进视频。

---

### 接口二：`POST /media/hls/process` — HLS 分片转码

#### 第一步：ffprobe 探测

```java
FFmpegProbeResult probeResult = ffprobe.probe(formatPath(tempInput.toString()));
```

探测结果用途：
- `width/height` → 判断自动生成哪些质量档位
- `duration` → 按 `segmentCount` 计算每段时长
- `fps` → 计算 GOP 大小
- `hasAudio` → 决定是否生成音频分流器

> **帧率是分数**：`30000/1001 ≈ 29.97` 是 NTSC 标准。`avg_frame_rate` 是 `Fraction` 对象，必须用 `getNumerator()/getDenominator()` 读取。

---

#### 第二步：GOP 计算

```
GOP = Group of Pictures = 两个关键帧（I帧）之间的帧数

HLS 要求：每个 .ts 切片的第一帧必须是关键帧
所以：GOP 大小 = 帧率 × 切片秒数

例：fps=29.97，segmentDuration=6秒
GOP = round(29.97 × 6) = round(179.82) = 180 帧
```

---

#### 第三步：filter_complex 多码率同时生成

等价的完整 FFmpeg 命令（以 1080p+720p 两档为例）：

```bash
ffmpeg -y -i input.mp4 \
  -filter_complex "
    [0:v]split=2[vin0][vin1];
    [0:a]asplit=2[ain0][ain1];
    [vin0]scale=w=1920:h=1080:force_original_aspect_ratio=decrease:flags=lanczos,
          pad=1920:1080:(ow-iw)/2:(oh-ih)/2[vout0];
    [vin1]scale=w=1280:h=720:force_original_aspect_ratio=decrease:flags=lanczos,
          pad=1280:720:(ow-iw)/2:(oh-ih)/2[vout1]
  " \
  -map [vout0] -c:v:0 libx264 -preset fast -b:v:0 4000k -maxrate:v:0 4800k -bufsize:v:0 8000k \
               -pix_fmt:v:0 yuv420p -g:v:0 180 -keyint_min:v:0 180 -sc_threshold:v:0 0 \
               -profile:v:0 main \
  -map [ain0]  -c:a:0 aac -b:a:0 128k -ac:a:0 2 -profile:a:0 aac_low \
  -map [vout1] -c:v:1 libx264 -preset fast -b:v:1 2000k -maxrate:v:1 2400k -bufsize:v:1 4000k \
               -pix_fmt:v:1 yuv420p -g:v:1 180 -keyint_min:v:1 180 -sc_threshold:v:1 0 \
               -profile:v:1 main \
  -map [ain1]  -c:a:1 aac -b:a:1 128k -ac:a:1 2 -profile:a:1 aac_low \
  -var_stream_map "v:0,a:0 v:1,a:1" \
  -hls_time 6 -hls_playlist_type vod -hls_flags independent_segments \
  -master_pl_name master.m3u8 \
  -hls_segment_filename "/tmp/hls-output-xxx/v%v/segment%03d.ts" \
  "/tmp/hls-output-xxx/v%v/index.m3u8"
```

关键参数说明：

| 参数 | 含义 |
|---|---|
| `force_original_aspect_ratio=decrease` | 按比例缩小，不拉伸，不超过目标尺寸 |
| `pad=W:H:(ow-iw)/2:(oh-ih)/2` | 宽高比不匹配时，用黑边居中填充 |
| `flags=lanczos` | 高质量缩放算法 |
| `-g:v:i N` | GOP 大小（两关键帧间距） |
| `-keyint_min:v:i N` | 最小 GOP，与 `-g` 相同可强制固定间距 |
| `-sc_threshold:v:i 0` | 禁用场景切换自动插关键帧（防止打乱 GOP 对齐） |
| `-profile:v main` | H.264 Main Profile，兼容绝大多数设备 |
| `-profile:a aac_low` | AAC-LC，最广泛兼容的 AAC 子集 |
| `-ac:a:i 2` | 强制输出双声道 |
| `-var_stream_map` | 告诉 HLS muxer 哪些视频/音频 stream 配对成一组 |
| `v%v` | FFmpeg 变量替换，`%v` = stream index（0, 1, 2...） |
| `segment%03d.ts` | 切片文件名，`%03d` = 0-padded 3位数字 |
| `-hls_playlist_type vod` | 生成点播类型 M3U8（完整列表） |
| `-hls_flags independent_segments` | 每个 .ts 切片可独立解码 |

---

### 接口三：`POST /media/image/process` — 图片处理

无 ffprobe 探测，完全用 `FFmpegBuilder` Java API：

```bash
# 裁剪 + 缩放 + 旋转
ffmpeg -i input.jpg -vf "crop=640:360:0:0, scale=320:180, rotate=90*PI/180" \
       -f image2 output.jpg
```

> **`-1` vs `-2` 的区别**：图片缩放用 `-1`（仅保持比例），视频用 `-2`（保持比例且结果必须是偶数，yuv420p 格式要求）。

---

### 接口四：`POST /media/audio/process` — 音频处理

无 ffprobe 探测，最简单的接口：

```bash
# 裁切 + 调音量
ffmpeg -i input.mp3 -ss 10 -t 30 -af "volume=1.5" output.mp3
```

---

## 三、接口调用规律汇总

```
┌─────────────────────────────────────────────────────────────────┐
│                        接口调用规律汇总                           │
├────────────────┬──────────────┬──────────────────────────────────┤
│ 接口            │ ffprobe       │ ffmpeg                          │
├────────────────┼──────────────┼──────────────────────────────────┤
│ /video/process │ 读宽高/帧率   │ 转码/裁切/缩放/字幕烧录           │
│ /hls/process   │ 读宽高/帧率/  │ filter_complex 多码率并行转 HLS  │
│                │ 时长/hasAudio │                                  │
│ /image/process │ 无            │ 裁切/缩放/旋转                    │
│ /audio/process │ 无            │ 裁切/音量调节                     │
└────────────────┴──────────────┴──────────────────────────────────┘
```

**两种调用方式的选择原则**：

| 方式 | 使用场景 |
|---|---|
| `FFmpegExecutor` (Java API) | 标准转码、图片、音频、字幕转换 |
| `ProcessBuilder` (手动拼命令) | 需要精确控制参数顺序（如 input-side `-ss` 必须在 `-i` 之前） |

---

## 四、FFprobe / FFmpeg 核心参数速查

> 精简版，聚焦最常用、最重要的参数，去除干扰信息。

---

### ffprobe 核心用法

ffprobe 在本项目中只做一件事：**读取媒体文件的元数据**。

```bash
ffprobe -v quiet -print_format json -show_streams -show_format <input>
```

| 读取字段 | Java 访问路径 | 用途 |
|---|---|---|
| 视频宽高 | `videoStream.width / .height` | 校验 crop 越界；决定 HLS 码率档位 |
| 帧率（分数） | `avg_frame_rate.getNumerator() / getDenominator()` | 计算 HLS GOP 大小 |
| 视频时长 | `probeResult.getFormat().duration` | 按段数计算 HLS 切片时长 |
| 是否有音轨 | `stream.codec_type == AUDIO` | HLS 是否生成 asplit 音频分流 |

**关键规则**：帧率是分数，不能直接用浮点读取，必须用 `Numerator/Denominator` 手动除。

---

### ffmpeg 核心参数分类速查

#### 1. 输入/输出控制

| 参数 | 作用 | 示例 |
|---|---|---|
| `-y` | 覆盖输出文件，不询问 | `-y` |
| `-i` | 指定输入文件 | `-i input.mp4` |
| `-ss` | seek 起始时间（**位置决定精度**） | `-ss 00:01:30` |
| `-t` | 持续时长（从 seek 点开始） | `-t 30` |

> **seek 位置规则**：
> - `-ss` 在 `-i` **之前** → input-side seek，跳关键帧，**快但不精确**
> - `-ss` 在 `-i` **之后** → output-side seek，逐帧解码，**慢但精确**

---

#### 2. 视频编码

| 参数 | 作用 | 常用值 |
|---|---|---|
| `-c:v` | 视频编码器 | `libx264` / `copy` |
| `-crf` | 恒定质量（0=无损，51=最差，推荐 18-28） | `-crf 23` |
| `-b:v` | 目标码率 | `-b:v 2000k` |
| `-maxrate` | 最大码率（配合 `-bufsize` 使用） | `-maxrate 2400k` |
| `-bufsize` | VBV 缓冲区（通常设为 2×bitrate） | `-bufsize 4000k` |
| `-preset` | 编码速度/压缩率权衡 | `ultrafast` → `medium` → `veryslow` |
| `-pix_fmt` | 像素格式（浏览器/设备兼容性要求） | `yuv420p` |
| `-profile:v` | H.264 规格（影响设备兼容性） | `main` / `high` |

> **CRF vs 码率 VBV 选择**：
> - 存档/质量优先 → 用 `-crf`
> - 带宽受控/直播/HLS → 用 `-b:v -maxrate -bufsize`

---

#### 3. 音频编码

| 参数 | 作用 | 常用值 |
|---|---|---|
| `-c:a` | 音频编码器 | `aac` / `copy` |
| `-b:a` | 音频码率 | `-b:a 128k` |
| `-ac` | 声道数 | `-ac 2`（双声道） |
| `-af` | 音频滤镜 | `-af "volume=1.5"` |
| `-profile:a` | AAC 子集 | `aac_low`（= AAC-LC，兼容性最好） |

---

#### 4. 视频滤镜 `-vf`

| 滤镜 | 语法 | 说明 |
|---|---|---|
| `crop` | `crop=w:h:x:y` | 裁剪，(x,y) 是左上角坐标 |
| `scale` | `scale=w:h:flags=lanczos` | 缩放，`-2` = 自动保持比例且对齐2 |
| `rotate` | `rotate=角度*PI/180` | 旋转，单位是弧度 |
| `subtitles` | `subtitles='file.ass'` | 烧录字幕（必须放滤镜链最后） |
| `pad` | `pad=W:H:(ow-iw)/2:(oh-ih)/2` | 居中填充黑边 |

**滤镜链执行顺序**：`crop → scale → subtitles`（顺序不能乱）

---

#### 5. HLS 专用参数

| 参数 | 作用 | 说明 |
|---|---|---|
| `-g` | GOP 大小（关键帧间距帧数） | 必须等于 `fps × 切片秒数` |
| `-keyint_min` | 最小 GOP | 设置与 `-g` 相同，强制固定间距 |
| `-sc_threshold 0` | 禁用场景切换自动关键帧 | 防止破坏 GOP 对齐 |
| `-hls_time` | 每个 .ts 切片的目标秒数 | `6`（常用值） |
| `-hls_playlist_type vod` | 点播模式 M3U8 | 完整列表，区别于 live 模式 |
| `-hls_flags independent_segments` | 每片可独立解码 | HLS 标准要求 |
| `-var_stream_map` | 视频/音频 stream 配对关系 | `"v:0,a:0 v:1,a:1"` |
| `-master_pl_name` | master playlist 文件名 | `master.m3u8` |
| `-hls_segment_filename` | 切片文件路径模板 | `v%v/segment%03d.ts` |

---

#### 6. filter_complex（多路流处理）

| 语法 | 作用 |
|---|---|
| `[0:v]split=N[vin0][vin1]...[vinN]` | 把输入视频流复制成 N 路 |
| `[0:a]asplit=N[ain0][ain1]...[ainN]` | 把输入音频流复制成 N 路 |
| `[vinX]scale=...[voutX]` | 对第 X 路做缩放处理 |
| `-map [voutX]` | 选择第 X 路视频输出 |
| `-c:v:X` | 对第 X 路视频流指定编码器 |

> filter_complex 是**有向图模型**：`[输入标签]滤镜[输出标签]`，分号分隔多条链路。
