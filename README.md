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
**Description**: Process video files including clipping, cropping, transcoding, bitrate adjustment, and adding subtitles with styling options.
**描述**: 处理视频文件，支持视频剪辑、画面裁剪、格式转码、码率调整以及添加带有样式选项的字幕。

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
| `bitrate` | Long | No | The video bitrate in kbps (e.g., 2000 for 2Mbps). | 视频比特率，单位 kbps（例如 2000 代表 2Mbps）。 |
| `subtitle` | Object | No | Subtitle configuration object. | 字幕配置对象。 |

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

#### X/Y Coordinate Rules / X/Y 坐标规则

1. **Coordinate System / 坐标系**
   - `X` is the horizontal coordinate from the **left** edge, `Y` is the vertical coordinate from the **top** edge.
   - 坐标原点在左上角：`X` 为水平方向（从左向右），`Y` 为垂直方向（从上向下）。
   - The coordinates are interpreted in the internal ASS script resolution (`PlayResX`, `PlayResY`), not directly in final video pixels.
   - 坐标是基于 ASS 脚本的内部分辨率（`PlayResX` / `PlayResY`）来理解的，而不是直接等同于最终视频像素。

2. **Practical Range / 推荐范围**
   - In practice, `PlayResX` / `PlayResY` are set by FFmpeg when converting SRT → ASS and are usually in a range like `384x288`, `640x360`, etc.
   - 实际运行时，`PlayResX` / `PlayResY` 由 FFmpeg 在 SRT 转 ASS 时写入，通常是 `384x288`、`640x360` 等固定值。
   - To ensure subtitles are visible on screen, keep X and Y within the rough range of the internal resolution:
     - `0 ≤ X ≤ PlayResX`
     - `0 ≤ Y ≤ PlayResY`
   - 为确保字幕一定在画面内显示，建议让 X/Y 大致落在内部分辨率范围内：
     - `0 ≤ X ≤ PlayResX`
     - `0 ≤ Y ≤ PlayResY`

3. **Why some large values are invisible? / 为什么某些较大的坐标看不到字幕？**
   - If you set a very large Y (for example `Y:1180`) while `PlayResY` is only `360`, the subtitle will be placed **below** the visible canvas and will not be rendered.
   - 如果 `PlayResY` 只有 `360`，但你写了 `Y:1180`，字幕在内部坐标系中已经落在“画布之外”，播放器会直接不渲染该行字幕。
   - This explains why changing from small coordinates (`X:50 Y:30`) to very large ones (`X:360 Y:1180`) may cause some lines to disappear.
   - 这也是为什么从小坐标（`X:50 Y:30`）换成很大的坐标（`X:360 Y:1180`）后，有些字幕会“看不见”的原因。

4. **Recommended Usage / 推荐使用方式**
   - For most cases, keep X/Y in a moderate range (for example X/Y within a few hundred units) instead of using very large numbers.
   - 一般情况下建议 X/Y 使用较温和的数值（几百以内），不要用过大的数值。
   - If you want the subtitle near the bottom, you can:
     - Use `alignment = 2` (bottom-center) and a relatively small Y;
     - Or test with several Y values until it visually fits your video.
   - 如果希望字幕靠近底部：
     - 可以设置 `alignment = 2`（底部居中），并配合较小的 Y；
     - 或通过多次调整 Y 值来找到合适的位置。

> Note: the current implementation does **not** automatically map X/Y to the final cropped video resolution.
> Coordinates are applied directly in ASS space, so extreme values may fall outside the visible area.
> 注意：当前实现**不会**自动根据最终裁剪后的视频分辨率去缩放 X/Y，坐标会直接用于 ASS 脚本空间，因此极端值可能落在可视区域之外。

### Example Request / 请求示例
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
  "bitrate": 2000,
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
**Description**: Transcode a video into multi-bitrate HLS streams (m3u8/ts) based on its resolution and store them in MinIO.
**描述**: 根据视频分辨率将其转码为多码率 HLS 流 (m3u8/ts) 并存储到 MinIO 中。

### Request Body Parameters / 请求体参数

| Parameter (参数) | Type (类型) | Required (必填) | Description (English) | Description (中文) |
| :--- | :--- | :--- | :--- | :--- |
| `objectName` | String | **Yes** | The name of the input video file stored in MinIO. | MinIO 中的输入视频文件名（相对路径）。 |
| `targetPath` | String | **Yes** | The target directory path in MinIO to store the HLS files. | MinIO 中存储 HLS 文件的目标目录路径。 |

### Example Request / 请求示例
```json
{
  "objectName": "movies/avatar.mp4",
  "targetPath": "hls/avatar_stream"
}
```

### Response / 响应
```json
{
  "message": "HLS processing completed",
  "streams": [
    {
      "resolution": "1920x1080",
      "m3u8Path": "hls/avatar_stream/stream_0.m3u8",
      "url": "http://minio-server:9000/bucket/hls/avatar_stream/stream_0.m3u8"
    },
    {
      "resolution": "1280x720",
      "m3u8Path": "hls/avatar_stream/stream_1.m3u8",
      "url": "http://minio-server:9000/bucket/hls/avatar_stream/stream_1.m3u8"
    },
    {
      "resolution": "854x480",
      "m3u8Path": "hls/avatar_stream/stream_2.m3u8",
      "url": "http://minio-server:9000/bucket/hls/avatar_stream/stream_2.m3u8"
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
