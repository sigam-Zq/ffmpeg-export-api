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

## 2. Image Processing / 图片处理
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

## 3. Audio Processing / 音频处理
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
