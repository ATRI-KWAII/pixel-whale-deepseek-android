# 像素鲸语

![像素鲸语图标](res/drawable/ic_launcher.png)

一个原生 Android DeepSeek 聊天客户端，采用卡通像素鲸鱼界面。2.3 版接入 DeepSeek 应用 API 的联网搜索、链接读取、文档输入和安全检查。

这是独立开发的客户端，与 DeepSeek 官方无隶属关系。

## 功能

- 多个独立会话；新建、搜索、重命名、删除、清空和分享会话文本。
- 每个会话有一份可编辑的默认系统提示词，也可独立选择 DeepSeek Flash 快速问答、Flash 深度思考或 V4 Pro 深度思考。
- 流式显示回复，可停止生成、复制、再次编辑问题、重新生成回答。
- 手动压缩较早上下文；当活跃上下文估算超过 10000 token 时自动压缩。最近 8 条消息保留原文，摘要可查看与复制。压缩使用一次或多次 DeepSeek API 请求，会产生相应费用；摘要不能保证保留所有细节。
- 发送图片与文字一起提问。图片会在本机缩小、去除原始元数据并保存到应用私有空间；图片问答使用 DeepSeek Flash，V4 Pro 仅用于纯文本会话。
- 输入栏上方可开启联网搜索和链接读取（开关对所有会话生效）；DeepSeek 会按问题决定是否调用工具，搜索回答显示来源链接。联网工具仅支持 Flash，开启后使用 Pro 的会话会切换到 Flash。
- 可选择 PDF、Office、图片和常见文本等文档进行问答。文档先上传到 DeepSeek Files API 完成解析，文件 ID 保留在本机会话中以供后续轮次复用。文档上传与解析、联网问答可能产生 API 费用；上传到 DeepSeek 的文件由其服务保存，本地删除会话不会自动删除远端文件。
- Flash 问答与上下文压缩启用 DeepSeek `safety_check: high`；流式拒答会替换已显示的临时内容。
- 基础 Markdown 显示；长会话每次只渲染最近 40 条消息，可按需加载更早内容，图片缩略图异步加载。

## 安装

从 [最新 Release](https://github.com/ATRI-KWAII/pixel-whale-deepseek-android/releases/latest) 下载 APK，复制到 Android 6.0 或更高版本的手机，打开文件并允许该来源安装应用。首次打开后点右上角“⋯”→“API Key 设置”，填写自己的 DeepSeek API Key。可以直接覆盖安装此前提供的 1.0、2.0 或 2.1 APK；旧版的单一聊天记录会迁移到“之前的聊天”会话。自行重新签名的 APK 无法直接覆盖安装。

## 隐私与网络

- 问答与摘要请求发往 `https://api.deepseek.com/chat/completions`，文档上传发往 `https://api.deepseek.com/files`。使用 DeepSeek 官方文档当前列出的 `deepseek-flash` 与 `deepseek-v4-pro` 模型。图片随当前会话的请求一起发送；应用不请求相册读取权限，附件选择由系统选择器完成。
- API Key 在 Android Keystore 中使用 AES-GCM 加密后存入应用私有空间，不写入 APK。
- 会话、系统提示词、聊天记录、图片副本和压缩摘要保存在应用私有空间。每次请求只携带当前会话的系统提示词、压缩摘要和未压缩的消息。删除或清空会话也会删除其中的图片副本。
- 卸载应用会删除本地数据；清除 Key 可在设置中完成。

## 构建

源代码使用 Android SDK 35 的命令行工具、JDK 17 以上和 PowerShell 构建。设置 `ANDROID_SDK_ROOT` 环境变量后运行 `./build.ps1`，或使用 `./build.ps1 -SdkRoot <SDK路径>`。APK 默认输出到项目的 `dist` 目录。构建脚本在本机生成开发签名；该密钥不提交到仓库。发布到应用商店前应改用自己的正式签名。
