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


## 1.8.1（15）通知与通信修复

- Debug、Release、AndroidTest 均通过本地离线构建。Release 仍为 unsigned。
- 独立包名模型执行器在 PKJ110 上通过：属性被系统重置后校正、连续绘制不重置文字、窄英文视口、关闭滚动后重挂载、复用和四字静态显示；Binder 死亡只重试一次，权限拒绝不重试。该临时测试包已移除。
- 经用户授权，在移除原签名正式版后安装 Debug 1.8.1。Root 部署成功、监听已连接，看护进程在运行。
- 独立测试 APK 发出的连续通知实际经过监听器中转，回调至提交通知为 6ms / 5ms。该数值不包含源应用推送、系统排队或岛动画；不能据此声称全部消息都在此耗时内显示。
- 初次完整服务回归的独立来源阶段失败：测试 APK 通知权限未授权。后续授予测试 APK 权限，使用 Provider 发布独立通知验证成功。授权后完整执行器的广播来源阶段仍失败；使用 Root 私有 Provider 直接在独立 APK 中发布通知的验证通过，不能把广播用例列为通过。
- 原签名正式版的通信失败未取得异常堆栈；新版加入断连恢复、Root 请求去重与 UI 超时。设备记录到 ColorOS o-stop(40) 同时停止两个应用进程，无法据此唯一确定该提示的原因。
- 用户确认 SystemUI 作用域后重新启动 SystemUI；测试岛间隔 2 秒的截图分别显示“字的滚动…”与“通知…”，确认真实右侧滚动；不代表覆盖所有锁屏、焦点、视图重建情形。
- 使用独立测试 APK Provider 的真机序列通过：首条提交 17ms、设置进程关闭后第二条提交 5ms、下岛恢复上一条、同 key 更新持续抑制、移除重发再次展示、2 秒到期退出且监听保留。
- 测试包和测试消息已清理，恢复 QQ、10 秒、手动下岛按钮关闭、滚动开启；Root 看护和已授权监听恢复连接。
- 测试广播通路在该环境失败，设备日志含其他模块的 system_server Hook 与 ColorOS o-stop；未修改其他模块策略，也未将失败归因为某个特定第三方模块。


## 1.8.2（16）Root 通信修复

- Debug APK 显示版本 `1.8.2-debug`；Release 构建元数据已确认为 `1.8.2-release`。
- 真机复现到 `BadParcelableException: ClassNotFoundException: com.bajiao.notificationisland.MainActivity$3`：匿名 `ResultReceiver` 从设置进程传入 monitor 时失败，Root 命令没有执行。
- 移除跨进程 `ResultReceiver`，改为请求 ID、monitor 状态字段和设置页轮询。新版 Root 命令实际返回 `exit=0`，耗时 203ms；`root_enabled=true`，看护脚本、开机脚本和 `enabled` 文件存在。

- 1.8.2 Debug、Release、AndroidTest 全部构建通过；真机设置页“停止保活 → 再次启用”通过，状态为已授权，按钮恢复可点击，monitor 的 `oom_score_adj=-900`。

- 手动结束 monitor 后 PID 从 5346 变为 22905，随后监听恢复连接；最终 Debug 覆盖安装后 Root 状态保留为已授权、监听已连接。此项不能单独区分系统绑定与 Root 看护对恢复的贡献。

- 五字宽布局版 Debug、Release、AndroidTest 离线构建通过；模型回归覆盖短正文固定宽度、关闭滚动、旧式群聊冒号拆分、URL 保留及 MessagingStyle 优先。用户在左侧补空白版本确认滚动恢复；随后仅改左侧名称居中，已安装 Debug 并重载 SystemUI。最后截图受前台聊天和消息切换影响，未独立确认全部名称长度下的视觉居中。
- 真机最终监听已连接、Root 已启用，停留时长恢复 15 秒，临时测试 APK 已移除。GitHub Actions 的 JDK 配置改为 21，与新增守护进程配置一致；本地构建不等同于远程 CI 已通过。

- 推送开关版 Debug、Release、AndroidTest 构建通过；真机模型及服务回归通过，覆盖关闭后移除展示/测试状态、监听保留、Root 设置不变、关闭期间测试入口无效、重新开启恢复展示。测试恢复 QQ/微信、15 秒及原显示选项。
- 延迟优化版使用独立测试来源成功提交，最近一次回调到提交为 4ms；服务回归初次读取中转通知失败，清理来源后复测通过。真机另记录到一次 136ms，因此仍有偶发延迟，不能声称已消除；该耗时不包含系统投递前等待及流体云动画。
