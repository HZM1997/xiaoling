# Release 包交付

当前只交付 `android-compose` 生成的签名 Release APK,不再构建早期网页或 WebView 版本。

GitHub Actions 产物只上传以下文件,避免 APK 和内含同一 APK 的 ZIP 重复占用下载体积:

- `xiaoling-v1.0.15-release.zip`
- `xiaoling-v1.0.15-release.apk.sha256`

ZIP 内包含 `xiaoling-v1.0.15-release.apk`。微信传输建议发送 ZIP。解压后保持 APK 文件名以 `.apk` 结尾;若微信或下载工具自动添加
`.1`,删除额外的 `.1` 后再安装。安装前可使用 SHA-256 文件核对包体完整性。

Release 使用仓库配置的正式 keystore 签名。本地没有相同 keystore 时只能做编译验证,
不能生成可覆盖安装的同签名包。
