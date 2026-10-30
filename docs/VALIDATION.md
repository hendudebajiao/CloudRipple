# 源码发布整理验证记录

本记录针对云漾 **1.8.0 / versionCode 14** 的源码整理；不增加手机行为测试通过范围。

## 已完成

- 从白名单导出目录构建，未复制 `local.properties`、参考工程、libxposed 子模块或编译产物。
- 用已有本地 Maven/Gradle 缓存、JDK 17 和 Android SDK 执行 `:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --no-daemon --max-workers=1`，**BUILD SUCCESSFUL，99 个任务执行**。
- 检查 Debug / Release APK 中的 `META-INF/xposed/module.prop`、`java_init.list`、`scope.list` 与看护脚本资源，入口和版本信息匹配。
- ZIP 完整性检查通过，逐文件 SHA-256 校验通过，XML 可解析，Markdown 本地文档链接目标存在。
- 导出包排除 `.git`、`.gradle`、`.diagnostics`、任意 `build`、设备数据、APK、签名密钥、模板解压目录和源码参考副本。
- 修正原来仍为 1.4.0 / 7 的 LSPosed 元数据至 1.8.0 / 14，名称为云漾、作者八角。
- 最终再次导出干净目录；构建过程中产生的缓存和 APK 不回填源码包。

## 未声称完成

- GitHub Actions 尚未远程执行；本次使用现有缓存，不代表已验证全新机器的联网下载。
- Release 产物未配置发行签名，本次未上传 GitHub、未创建远程仓库、未发布 APK。
- 此次整理没有再次安装手机或重新执行通知/底栏手势真机测试。
- 图标的再分发权利及模板缺失的许可声明需作者据实核对，见第三方来源与发布说明。

源码导出记录见 ZIP 旁的 SHA-256 文件，包内 `SOURCE_MANIFEST.sha256` 记录源文件哈希。校验清单用于核对完整性，不是数字签名。
