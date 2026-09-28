# 古老的爬虫脑

一个面向 Android 的极简 AI 对话客户端，支持本地保存多轮聊天记录、Markdown 渲染、SSE 流式输出，以及图片、PDF 和文本附件。

## 功能

- 使用 Custom API 的 Responses API，也兼容 Chat Completions 格式；
- 支持流式回答和 Markdown 展示；
- 使用 Room 在本地保存多个会话与历史上下文；
- 支持图片、PDF、文本等附件；图片会在发送前适度缩放和压缩；
- API 地址、访问令牌和模型名称均可在应用设置中配置；
- 简约的 Swiss Style 界面与自定义应用图标。

## 配置

打开应用的“设置”，填写：

1. API 地址，例如 `https://your-api.example.com/v1`；
2. 访问令牌；
3. 模型名称，例如 `gpt-5.5`。

应用默认使用 Responses API，请确保中转站提供以下接口：

```text
POST {API 地址}/responses
```

代码层面保留了 Chat Completions 请求分支，当前应用界面默认使用 Responses API。

## 本地构建

项目使用 Kotlin、Jetpack Compose、Room 和 Gradle。使用 Android Studio 打开项目，或在配置好 JDK 17 与 Android SDK 后运行：

```bash
gradle assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 数据与隐私

聊天记录和连接设置保存在设备应用数据目录中。卸载应用通常会清除这些数据；用户选择的原始附件仍保留在手机原来的位置。请只使用你信任的 API 中转站，并妥善保管访问令牌。

## 当前版本

当前发布版本为 `v1.0.0`，对应调试 APK 通过 GitHub Releases 提供下载。
