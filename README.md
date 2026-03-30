# Video Processing Service API Documentation / 视频处理服务 API 文档

This project provides a RESTful API for processing media files (video, image, audio) using FFmpeg and MinIO.
本项目提供了一套基于 FFmpeg 和 MinIO 的 RESTful API，用于处理视频、图片和音频文件。

## Base URL / 基础 URL
All endpoints are relative to the base URL of the application (e.g., `http://localhost:8080`).
所有接口均基于应用的基础 URL（例如 `http://localhost:8080`）。

---

## 1. Video Processing / 视频处理
**Endpoint**: `/media/video/process`
**Method**: `POST`
**Description**: Process video files including clipping, cropping, transcoding, scaling, bitrate/quality adjustment, and adding subtitles with styling options.
**描述**: 处理视频文件，支持视频剪辑、画面裁剪、缩放、格式转码、码率/质量调整以及添加带有样式选项的字幕。

### Request Body Parameters / 请求体参数

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `objectName` | String | **Yes** | The name of the input video file stored in the MinIO bucket. | 存储在 MinIO Bucket 中的输入视频文件名。 |
| `outputFormat` | String | No | The desired output format (file extension). Defaults to `"mp4"`. | 期望的输出格式（文件后缀）。默认为 `"mp4"`。 |
| `startTime` | Double | No | The start time for clipping the video (in seconds). | 视频剪辑的开始时间（秒）。 |
| `duration` | Double | No | The duration of the clip (in seconds). | 视频剪辑的持续时长（秒）。 |
| `cropX` | Integer | No | The X-coordinate of the top-left corner for cropping. Defaults to 0 if cropWidth/Height are set. | 裁剪区域左上角的 X 坐标。默认为 0。 |
| `cropY` | Integer | No | The Y-coordinate of the top-left corner for cropping. Defaults to 0 if cropWidth/Height are set. | 裁剪区域左上角的 Y 坐标。默认为 0。 |
| `cropWidth` | Integer | No | The width of the crop area. Both `cropWidth` and `cropHeight` must be set to enable cropping. | 裁剪区域的宽度。必须同时设置高度才生效。 |
| `cropHeight` | Integer | No | The height of the crop area. Both `cropWidth` and `cropHeight` must be set to enable cropping. | 裁剪区域的高度。必须同时设置宽度才生效。 |
| `scaleWidth` | Integer | No | Output scale width in pixels. Use `-2` to auto-calculate based on aspect ratio (requires `scaleHeight`). | 输出缩放宽度（像素）。设为 `-2` 可按比例自适应（需同时指定 `scaleHeight`）。 |
| `scaleHeight` | Integer | No | Output scale height in pixels. Use `-2` to auto-calculate based on aspect ratio (requires `scaleWidth`). | 输出缩放高度（像素）。设为 `-2` 可按比例自适应（需同时指定 `scaleWidth`）。 |
| `videoCodec` | String | No | Video encoder. Defaults to `"libx264"`. Use `"libx265"` for better compression, or `"copy"` to skip re-encoding. | 视频编码器。默认 `"libx264"`。`"libx265"` 压缩率更高，`"copy"` 跳过重编码（仅做容器转换/剪辑）。 |
| `crf` | Integer | No | Constant Rate Factor for quality-based encoding (0–51, lower = better quality). Recommended: `18`–`28` for libx264. **Overrides `bitrate` when set.** | 恒质量系数（0–51，值越小质量越高）。推荐范围 18–28。**设置后将忽略 `bitrate`。** |
| `bitrate` | Long | No | Target video bitrate in kbps (e.g., `2000` for 2 Mbps). Ignored if `crf` is set. | 目标视频码率（kbps，例如 `2000` 表示 2 Mbps）。设置 `crf` 时此参数无效。 |
| `preset` | String | No | libx264/libx265 encoding speed preset. Options: `ultrafast` / `superfast` / `veryfast` / `faster` / `fast` / `medium`(default) / `slow` / `slower` / `veryslow`. Slower = better compression at same quality. | 编码速度预设。越慢压缩率越高（文件越小），CPU 消耗越大。默认 `medium`。 |
| `profile` | String | No | H.264 profile: `"baseline"` / `"main"` / `"high"`. Use `"baseline"` for maximum device compatibility. | H.264 Profile。`"baseline"` 兼容性最好（无 B 帧），`"high"` 压缩效率最高。 |
| `level` | String | No | H.264 level, e.g. `"4.0"`, `"4.1"`, `"5.0"`. Controls max bitrate and resolution constraints. | H.264 Level，如 `"4.0"`，控制最大码率和分辨率上限。 |
| `audioCodec` | String | No | Audio encoder. Defaults to `"aac"`. Use `"mp3"` for MP3 output, or `"copy"` to pass through original audio. | 音频编码器。默认 `"aac"`，可选 `"mp3"` 或 `"copy"`（直接复制原始音轨）。 |
| `audioBitrate` | Integer | No | Audio bitrate in kbps. Defaults to `128`. Common values: `96`, `128`, `192`, `256`. | 音频码率（kbps）。默认 `128`。 |
| `subtitle` | Object | No | Subtitle configuration object. | 字幕配置对象。 |

> **Encoding mode tips / 编码模式建议：**
> - **Quality priority (质量优先)**: Set `crf` (e.g., `23`) + `preset` (e.g., `slow`). No fixed bitrate.
> - **Bitrate priority (码率优先)**: Set `bitrate` (e.g., `2000`) without `crf`. Service auto-adds VBV (`maxrate`/`bufsize`).
> - **Fast copy (快速复制)**: Set `videoCodec: "copy"` and `audioCodec: "copy"` to skip re-encoding entirely.

### Subtitle Options (`subtitle`) / 字幕选项

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `objectName` | String | **Yes** | The name of the subtitle file (e.g., `.srt`) in MinIO. | MinIO 中的字幕文件名（如 `.srt`）。 |
| `fontSize` | Integer | No | Font size for the subtitles. | 字幕字体大小。 |
| `fontColor` | String | No | Font color in ASS format (e.g., `&HFFFFFF`). | 字幕字体颜色（ASS 格式，如 `&HFFFFFF`）。 |
| `marginV` | Integer | No | Vertical margin (bottom distance). | 垂直边距（底部距离）。 |
| `marginL` | Integer | No | Left margin. | 左侧边距。 |
| `alignment` | Integer | No | Alignment (e.g., 2 for bottom-center). | 对齐方式（例如 2 表示底部居中）。 |

### Subtitle SRT Format and X/Y Rules / 字幕 SRT 格式与 X/Y 规则

The video processing API supports a **customized SRT format** to control subtitle position and style.
视频处理接口支持一种**带自定义坐标和样式的 SRT 格式**，用于精确控制字幕位置和样式。

#### Basic SRT Line Format / 基本 SRT 行格式

Each subtitle block follows the standard SRT structure, with an extra `X:` and `Y:` at the end of the timestamp line:
每一条字幕采用标准 SRT 结构，只是在时间轴行末尾增加了 `X:` 和 `Y:`：

```text
1
00:00:00,000 --> 00:00:07,150 X:50 Y:30
<font color="#ffffff" size="50">你好</font>

2
00:00:07,244 --> 00:00:14,744 X:360 Y:1180
<font color="#ffffff" size="50">Hello</font>
```

- `X:...` / `Y:...` are used as the **subtitle position**.  
  The service converts them to ASS `{\pos(x,y)}` internally.
- `X:` / `Y:` 用于定义**字幕位置**，服务内部会将其转换为 ASS 的 `{\pos(x,y)}`。

HTML-like `<font>` tags in the text are supported and will be converted to ASS styles:
字幕文本中支持 HTML 风格的 `<font>` 标签，最终会被转换为 ASS 样式：

- `color="#RRGGBB"` → mapped to ASS primary color (unless overridden by `fontColor` in request)
- `size="N"` → used as the base font size (overridden if `subtitle.fontSize` is provided)

`color` 和 `size` 属性会被解析：
- `color="#RRGGBB"` → 映射为 ASS 字幕主颜色（如果请求体中的 `fontColor` 不为空，则以请求为准）
- `size="N"` → 作为基础字体大小（如果请求体中的 `subtitle.fontSize` 有值，则以请求为准）

#### X/Y Coordinate Rules (Pixel-based) / X/Y 像素坐标规则

1. **Coordinate System / 坐标系**
   - `X` is the horizontal pixel coordinate from the **left** edge of the final video frame.
   - `Y` is the vertical pixel coordinate from the **top** edge of the final video frame.
   - If cropping is enabled (`cropWidth` / `cropHeight` are set), the coordinate system is the **cropped output region**:
     - `0 ≤ X ≤ cropWidth`
     - `0 ≤ Y ≤ cropHeight`
   - 坐标原点在最终输出画面的左上角：
     - `X` 为水平方向像素（从左向右）
     - `Y` 为垂直方向像素（从上向下）
   - 如果启用裁剪（设置了 `cropWidth` / `cropHeight`），坐标系对应于裁剪后的输出区域：
     - `0 ≤ X ≤ cropWidth`
     - `0 ≤ Y ≤ cropHeight`

2. **How it works internally / 内部转换规则**
   - The service treats X/Y as pixel coordinates in the final output frame (after cropping).
   - Internally, these pixel coordinates are mapped into the ASS script resolution (`PlayResX`, `PlayResY`) so that subtitles render at the correct visual position.
   - 您填写的 X/Y 会被视为最终输出画面上的像素坐标（已考虑裁剪）。
   - 服务内部会将这些像素坐标按比例映射到 ASS 的脚本分辨率（`PlayResX` / `PlayResY`），以确保字幕在画面中的视觉位置准确。

3. **Practical Notes / 使用注意事项**
   - X/Y values greater than the output width/height will be clamped to the frame edges.
   - 参数中的 X/Y 超出输出宽高范围时，会自动被限制在画面边界之内。
   - You can think of X/Y exactly as “video pixels” after applying your crop settings.
   - 可以直接把 X/Y 理解为“应用裁剪后的最终视频像素坐标”，不需要关心 ASS 的 PlayRes 细节。

### Example Request / 请求示例

**Quality-based encoding (CRF mode) / 质量优先模式：**
```json
{
  "objectName": "my_vacation.mp4",
  "outputFormat": "mp4",
  "startTime": 10.5,
  "duration": 60.0,
  "cropX": 100,
  "cropY": 100,
  "cropWidth": 1280,
  "cropHeight": 720,
  "crf": 23,
  "preset": "slow",
  "profile": "high",
  "level": "4.1",
  "audioCodec": "aac",
  "audioBitrate": 128,
  "subtitle": {
    "objectName": "captions.srt",
    "fontSize": 24,
    "fontColor": "&HFFFFFF",
    "marginV": 20,
    "marginL": 10,
    "alignment": 2
  }
}
```

**Bitrate-based encoding / 码率优先模式：**
```json
{
  "objectName": "my_vacation.mp4",
  "outputFormat": "mp4",
  "scaleWidth": 1280,
  "scaleHeight": -2,
  "bitrate": 2000,
  "preset": "fast",
  "audioCodec": "aac",
  "audioBitrate": 128
}
```

**Fast cut without re-encoding / 快速剪辑（不重编码）：**
```json
{
  "objectName": "long_video.mp4",
  "outputFormat": "mp4",
  "startTime": 30.0,
  "duration": 120.0,
  "videoCodec": "copy",
  "audioCodec": "copy"
}
```

### Response / 响应
```json
{
  "message": "Video processed successfully",
  "outputObject": "output-12345.mp4",
  "url": "http://minio-server:9000/bucket/output-12345.mp4"
}
```

---

## 2. HLS Transcoding (Multi-bitrate) / HLS 多码率切片
**Endpoint**: `/media/hls/process`
**Method**: `POST`
**Description**: Transcode a video into multi-bitrate HLS streams (m3u8/ts) based on its resolution and store them in MinIO. Supports custom resolutions, segment duration, encoding preset and audio bitrate.
**描述**: 根据视频分辨率将其转码为多码率 HLS 流 (m3u8/ts) 并存储到 MinIO 中。支持自定义分辨率、切片时长、编码预设及音频码率。

### Request Body Parameters / 请求体参数

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `objectName` | String | **Yes** | The name of the input video file stored in MinIO. | MinIO 中的输入视频文件名（相对路径）。 |
| `targetPath` | String | **Yes** | The target directory path in MinIO to store the HLS files. | MinIO 中存储 HLS 文件的目标目录路径。 |
| `resolutions` | List | No | List of custom resolutions. If not provided, auto quality ladder is applied (top 3 levels ≤ source resolution). | 自定义分辨率列表。如未提供，则自动选取不超过源分辨率的前 3 档质量等级。 |
| `segmentDuration` | Integer | No | Custom segment duration in seconds (`hls_time`). Default is `6`. | 自定义切片时长（秒）。默认 6 秒。 |
| `segmentCount` | Integer | No | Total number of segments. Overrides `segmentDuration` when set. Segment duration = `round(totalDuration / segmentCount)`. | 总切片数量。设置后覆盖 `segmentDuration`，切片时长 = `round(总时长 / 切片数)`。 |
| `preset` | String | No | libx264 encoding speed preset: `ultrafast` / `veryfast` / `fast`(default) / `medium` / `slow` / `veryslow`. | libx264 编码速度预设，默认 `fast`（兼顾 HLS 转码速度与压缩率）。 |
| `audioBitrate` | Integer | No | Audio bitrate per stream in kbps. Defaults to `128`. | 每路音频码率（kbps）。默认 128。 |

#### Resolution Object / 分辨率对象

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `width` | Integer | **Yes** | The width of the video stream. | 视频流的宽度（像素）。 |
| `height` | Integer | **Yes** | The height of the video stream. | 视频流的高度（像素）。 |
| `bitrate` | String | No | Target video bitrate (e.g., `"2000k"`). Auto-calculated from resolution if omitted. | 目标视频码率（如 `"2000k"`）。省略时根据分辨率自动估算。 |
| `maxrate` | String | No | Max bitrate cap for VBV (e.g., `"2400k"`). Defaults to `1.2 × bitrate`. | VBV 最大码率（如 `"2400k"`）。默认为 `1.2 × bitrate`。 |
| `bufsize` | String | No | VBV buffer size (e.g., `"4000k"`). Defaults to `2 × bitrate`. Larger bufsize allows more burst. | VBV 缓冲区大小（如 `"4000k"`）。默认为 `2 × bitrate`，越大允许的瞬时码率越高。 |

> **Auto quality ladder / 默认质量等级表（`resolutions` 未设置时）：**
>
> | Level | Width × Height | Bitrate | Maxrate | Bufsize |
> |---|---|---|---|---|
> | 4K   | 3840×2160 | 10000k | 12000k | 20000k |
> | 2K   | 2560×1440 | 6000k  | 7200k  | 12000k |
> | 1080p| 1920×1080 | 4000k  | 4800k  | 8000k  |
> | 720p | 1280×720  | 2000k  | 2400k  | 4000k  |
> | 480p | 854×480   | 1000k  | 1200k  | 2000k  |
> | 360p | 640×360   | 600k   | 720k   | 1200k  |
>
> Only levels whose width **and** height are ≤ the source resolution are included. At most 3 levels are used.  
> 仅保留宽高均不超过源视频的等级，最多取前 3 档。

### Example Request / 请求示例

**Auto quality ladder with encoding control / 自动质量等级 + 编码控制：**
```json
{
  "objectName": "movies/avatar.mp4",
  "targetPath": "hls/avatar_stream",
  "segmentDuration": 6,
  "preset": "fast",
  "audioBitrate": 128
}
```

**Custom resolutions with full VBV control / 自定义分辨率 + 精细码率控制：**
```json
{
  "objectName": "movies/avatar.mp4",
  "targetPath": "hls/avatar_stream",
  "segmentDuration": 4,
  "preset": "medium",
  "audioBitrate": 192,
  "resolutions": [
    {
      "width": 1920,
      "height": 1080,
      "bitrate": "4000k",
      "maxrate": "4800k",
      "bufsize": "8000k"
    },
    {
      "width": 1280,
      "height": 720,
      "bitrate": "2000k"
    }
  ]
}
```

### Response / 响应
```json
{
  "masterM3u8Path": "hls/avatar_stream/master.m3u8",
  "masterM3u8Url": "http://minio-server:9000/bucket/hls/avatar_stream/master.m3u8",
  "streams": [
    {
      "resolution": "1920x1080",
      "m3u8Path": "hls/avatar_stream/1920x1080/index.m3u8",
      "url": "http://minio-server:9000/bucket/hls/avatar_stream/1920x1080/index.m3u8"
    },
    {
      "resolution": "1280x720",
      "m3u8Path": "hls/avatar_stream/1280x720/index.m3u8",
      "url": "http://minio-server:9000/bucket/hls/avatar_stream/1280x720/index.m3u8"
    }
  ]
}
```

---

## 3. Image Processing / 图片处理
**Endpoint**: `/media/image/process`
**Method**: `POST`
**Description**: Process image files including scaling, cropping, rotating, and applying filters.
**描述**: 处理图片文件，支持缩放、裁剪、旋转以及应用自定义滤镜。

### Request Body Parameters / 请求体参数

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `objectName` | String | **Yes** | The name of the input image file stored in the MinIO bucket. | 存储在 MinIO Bucket 中的输入图片文件名。 |
| `outputFormat` | String | No | The desired output format (file extension). Defaults to `"jpg"`. | 期望的输出格式（文件后缀）。默认为 `"jpg"`。 |
| `cropX` | Integer | No | The X-coordinate of the top-left corner for cropping. Defaults to 0. | 裁剪区域左上角的 X 坐标。默认为 0。 |
| `cropY` | Integer | No | The Y-coordinate of the top-left corner for cropping. Defaults to 0. | 裁剪区域左上角的 Y 坐标。默认为 0。 |
| `cropWidth` | Integer | No | The width of the crop area. | 裁剪区域的宽度。 |
| `cropHeight` | Integer | No | The height of the crop area. | 裁剪区域的高度。 |
| `width` | Integer | No | The target width for scaling/resizing the image. | 缩放/调整大小的目标宽度。 |
| `height` | Integer | No | The target height for scaling/resizing the image. | 缩放/调整大小的目标高度。 |
| `rotate` | Double | No | The rotation angle in degrees (e.g., 90, 180, 270). | 旋转角度（如 90, 180, 270）。 |
| `filter` | String | No | A custom FFmpeg filter string to apply (e.g., "hflip" for horizontal flip). | 自定义 FFmpeg 滤镜字符串（如 "hflip" 表示水平翻转）。 |

### Example Request / 请求示例
```json
{
  "objectName": "photo.jpg",
  "outputFormat": "png",
  "width": 800,
  "height": 600,
  "rotate": 90
}
```

### Response / 响应
```json
{
  "message": "Image processed successfully",
  "outputObject": "output-67890.png",
  "url": "http://minio-server:9000/bucket/output-67890.png"
}
```

---

## 4. Audio Processing / 音频处理
**Endpoint**: `/media/audio/process`
**Method**: `POST`
**Description**: Process audio files including clipping, volume adjustment, and format conversion.
**描述**: 处理音频文件，支持音频剪辑、音量调整以及格式转换。

### Request Body Parameters / 请求体参数

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `objectName` | String | **Yes** | The name of the input audio file stored in the MinIO bucket. | 存储在 MinIO Bucket 中的输入音频文件名。 |
| `outputFormat` | String | No | The desired output format (file extension). Defaults to `"mp3"`. | 期望的输出格式（文件后缀）。默认为 `"mp3"`。 |
| `startTime` | Double | No | The start time for clipping the audio (in seconds). | 音频剪辑的开始时间（秒）。 |
| `duration` | Double | No | The duration of the clip (in seconds). | 音频剪辑的持续时长（秒）。 |
| `volume` | Double | No | The volume adjustment factor. `1.0` is original volume, `0.5` is 50% volume, `2.0` is 200% volume. | 音量调整系数。`1.0` 为原音量，`0.5` 为 50% 音量，`2.0` 为 200% 音量。 |

### Example Request / 请求示例
```json
{
  "objectName": "interview.wav",
  "outputFormat": "mp3",
  "startTime": 0.0,
  "duration": 120.0,
  "volume": 1.5
}
```

### Response / 响应
```json
{
  "message": "Audio processed successfully",
  "outputObject": "output-11223.mp3",
  "url": "http://minio-server:9000/bucket/output-11223.mp3"
}
```

---

## Docker Deployment / Docker 部署

The project includes a `Dockerfile` optimized for media processing.
本项目包含一个专为媒体处理优化的 `Dockerfile`。

### Key Features / 主要特性
- **Pre-installed FFmpeg**: The container comes with FFmpeg installed.
- **Chinese Font Support**: `fonts-noto-cjk` is installed to ensure correct rendering of Chinese subtitles.
- **JVM Support**: Built on a Java runtime environment.

### Docker Compose
A `docker-compose.yml` is provided to orchestrate the application and MinIO. It mounts the `config` directory to allow external configuration of `application.yaml`.
提供了 `docker-compose.yml` 来编排应用和 MinIO。它挂载了 `config` 目录，允许从外部配置 `application.yaml`。

```yaml
volumes:
  - ./config:/app/config
```
