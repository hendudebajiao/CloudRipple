# 上传 GitHub 的文件与发布流程

## 应该上传什么

上传整理后的 **源码目录内容**，保留 `.github`、`.gitignore` 等点开头文件。以下文件组成可构建仓库：

```text
README.md                       项目入口、功能、环境、构建
LICENSE                         GPL-3.0 完整文本
NOTICE                          作者和许可声明
THIRD_PARTY_NOTICES.md           第三方来源说明
PRIVACY.md                      权限与本地数据说明
.gitignore / .gitattributes      忽略规则与跨平台换行规则
.github/workflows/build.yml     云端构建工作流
build.gradle.kts                AGP 配置
settings.gradle.kts             Maven 仓库与 app 模块
gradle.properties               不含个人路径的通用构建参数
gradlew / gradlew.bat            Gradle 启动脚本
gradle/wrapper/                  Wrapper JAR 与版本信息
app/build.gradle.kts            应用 SDK / 版本 / 依赖
app/proguard-rules.pro           Release 保留规则
app/src/                        正式、Debug、设备测试源码与资源
docs/                           设计、使用、发布说明
scripts/export-source.py        可重复的白名单源码导出
third-party/licenses/           原有第三方许可证
SOURCE_MANIFEST.sha256          导出包各文件 SHA-256 清单
```

不要只上传 `app/`。根目录 Gradle 配置、Wrapper、模块元数据和资源同样是构建所必需的。当前通过 Maven 获取 libxposed API，没有本地源码依赖，因此不上传 `.gitmodules`、`libxposed-api/` 或解压模板。

## 不应上传什么

- `.gradle/`、任意 `build/`、Android Studio 私有状态、`local.properties`。
- `.diagnostics/` 中的通知、日志、设备数据库、SystemUI APK、聊天截图和设备标识。
- `libxposed-api-extract/`、`lsposed-template-extract/`、下载 ZIP、旧 `kernelsu/` 实验目录。
- 签名密钥、证书私钥、密码、token、`.env`，以及含凭证的 Gradle 配置。
- 导出目录 `release-source/` 和源码 ZIP 自身；上传解压后的目录内容，不将导出结果递归放回源码仓库。
- 历史 `.git/`。源码导出不会带历史仓库，也不会覆盖当前本地提交或 `v1.0.0` 标签。

编译出来的 APK 建议作为 GitHub Release 附件，源仓库保留构建源码。当前 Release APK 未签名，不能当作可安装正式版直接分发。

## 本次导出

在工程根目录执行：

```powershell
python scripts/export-source.py
```

脚本创建 `release-source/yunyang-1.8.0-source-时间戳/`、同名 ZIP 及 ZIP 的 SHA-256 校验文件。每次使用新目录，既不删除原工程，也不复制缓存和 Git 历史。ZIP 内包含一个顶层目录，解压后进入该目录即可构建。

图标已包含在资源目录。`app/src/main/assets/watchdog-v4.sh` 必须保留：它是 APK 内保活逻辑的一部分，不是需要另刷的 KernelSU 模块。

## 建立 GitHub 仓库

1. 在自己的 GitHub 账户新建空仓库，例如 `yunyang`。以下示例中的地址仅为命名示例，本次整理没有创建或上传远程仓库。
2. 解压源码 ZIP，进入顶层目录，用 Git 提交完整目录。项目自有代码许可已选择 GPL-3.0-only，无需 GitHub 再生成不同的 LICENSE。
3. 建议用终端提交，让点文件及二进制 Wrapper 保持完整。Windows 也可以直接在该目录运行 Git。

```bash
git init -b main
git add .
git update-index --chmod=+x gradlew
git diff --cached --stat
git commit -m "Release Yunyang 1.8.0 source"
# 将下面地址改成你实际创建的仓库
git remote add origin https://github.com/hendudebajiao/yunyang.git
git push -u origin main
```

当前开发目录已有仅提交 app 的早期基线 `v1.0.0`，这里使用干净导出目录建立新仓库，避免把旧诊断或参考副本混入历史。若决定沿用原仓库，需要另外审核其全部 Git 历史。

## 发布 APK

1. 核对 `app/build.gradle.kts` 与 `META-INF/xposed/module.prop` 的版本名/版本号同步，当前为 1.8.0 / 14。
2. 用固定发行证书签名 Release，并检查 LSPosed 入口、作用域、通知权限、真实消息和关闭界面后的恢复行为。
3. 给**实际发布源码提交**打版本标签，再建立 Release，附签名 APK、哈希和对应源码地址；自动构建工作流只生成构建产物，不自动发布 Release。
4. 同时保留 GPL、第三方许可、构建脚本和精确版本的对应源码。分发 APK 时不能只给一个不对应的最新源码链接。

不要把本机 Debug 证书或发行证书提交进 GitHub。公共 CI 的 Debug 签名适合测试，不等于稳定发行签名。

## 发布前的实际待核事项

- 确认作者提供图标的再分发权利；当前材料只记录收到 PNG，没有图片创作证明。
- 本地旧模板副本未带独立许可，准确上游记录仍需核实；见第三方来源说明。
- GitHub Actions 尚未在远程运行；真机的权限和 ColorOS 兼容性不能由构建工作流替代验证。
- 底栏拖动代码已加入，开发期间真机手势测试受到前台应用变化干扰，尚不应在 Release 说明中声称完整手势验证通过。

这些事项不会阻止本地整理和构建，但应在正式公开发行前据实核对。
