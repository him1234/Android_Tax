# 税费记账 1.1.0

Android 12 及以上可使用；工程保留原包名 `com.example.taxledger` 和 `ledger.db` 数据库结构（schema version 2），版本号由 3 / 1.0.2 升至 4 / 1.1.0。

## 新增与修复

- PDF 按页面渲染后进行中文 OCR，不再把 PDF 二进制当作可见文字；图片使用同一 OCR 模型。
- 摄像头实时 OCR 和二维码扫描，二维码中的 20 位数字用作发票号码。所有识别字段在录入页供人工确认。
- OFD 和 XML 导入沿用原解析方式；为 OFD 条目和所有附件设置大小限制；系统文件分享和“打开方式”可传入 PDF、OFD、XML、图片。
- 开票日期改为可选择日期，避免点击日期重置成今天。
- 季度页添加“一键导出本季度报告”；导出在后台生成，报告按章节、季度合计、人员、逐票明细排版。

## 本地构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0、Gradle 8.11.1（或 Android Studio 支持的 Gradle 自动下载环境）。原始源码包没有 `gradle-wrapper.jar`，因此第一次使用时先执行 `gradle wrapper --gradle-version 8.11.1`，然后 `./gradlew :app:testDebugUnitTest :app:assembleDebug`。项目的 GitHub Actions 流程也会生成 wrapper 并编译测试版 APK。

## 覆盖升级与数据

必须使用与已安装版本**相同的 applicationId 和签名证书**、更高的 versionCode，Android 才允许原位升级并保留应用私有数据。包名和数据库结构已保持不变；原 zip 中没有旧版签名私钥，因此默认 debug APK **不能保证**覆盖已安装版本。切勿卸载旧版后安装，否则私有账本和附件会丢失。

若持有旧版签名文件，以环境变量 `TAXLEDGER_KEYSTORE`、`TAXLEDGER_STORE_PASSWORD`、`TAXLEDGER_KEY_ALIAS`、`TAXLEDGER_KEY_PASSWORD` 提供原证书后运行 `./gradlew :app:assembleRelease`。签名前分别用 `apksigner verify --print-certs old.apk` 与 `apksigner verify --print-certs new.apk` 比对证书 SHA-256；相同后在测试设备执行 `adb install -r new.apk`，核对原有人员、发票、附件与设置。不要把密钥、密码提交到源码仓库。

OCR 离线在设备上运行；安装包体积会因内置的中文识别和二维码模型增加。识别结果需人工核对，报告用于内部对账。

## 自动发布到 GitHub Release

`.github/workflows/android-release.yml` 在推送 `v*` 标签后自动运行单元测试、编译签名的 Release APK，校验包名、版本号和签名证书，然后把 APK 与 `SHA256SUMS` 发布到相同标签的 GitHub Release。失败时不会执行发布步骤。日常 `main`/PR 构建继续使用 `android-build.yml`，只提供测试版构建产物。

先在仓库的 **Settings → Secrets and variables → Actions** 设置这五项 repository secrets：

| Secret | 内容 |
| --- | --- |
| `TAXLEDGER_KEYSTORE_BASE64` | 原应用签名 keystore 的 Base64 内容，不能使用新生成的密钥 |
| `TAXLEDGER_STORE_PASSWORD` | keystore 密码 |
| `TAXLEDGER_KEY_ALIAS` | 原签名密钥别名 |
| `TAXLEDGER_KEY_PASSWORD` | 原签名密钥密码 |
| `TAXLEDGER_EXPECTED_CERT_SHA256` | 已安装旧 APK 的证书 SHA-256 摘要 |

使用 `apksigner verify --print-certs old.apk` 取得旧签名证书摘要；Linux 上可以用 `base64 -w0 old.jks` 生成 keystore 的 Base64 文本。Base64 仅是编码，务必仅将其存入仓库 secret。将 `app/build.gradle.kts` 中的 `versionName` 设置为标签去掉 `v` 后的版本，并递增 `versionCode`，例如当前版本推送 `v1.1.0`：

```bash
git tag v1.1.0
git push origin v1.1.0
```

发布工作流使用仓库自带的 `GITHUB_TOKEN`，已将该 job 的权限限定为 `contents: write`。还需要在仓库 Actions 设置中允许工作流具有写入权限。自动发布需要工程已推送至你有权限管理的 GitHub 仓库；源码压缩包本身不会触发工作流。
