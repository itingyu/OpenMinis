# Minis 自编译版（com.minis.vox）

基于 OpenMinis `main` @ `1a71df0`（2026-10-26），GPLv3。

## 改了什么

### 1. 系统语音识别（本地 STT）在国产 ROM 上不可用
`SystemSpeechRecognitionEngine.isAvailable` 原来是
`SpeechRecognizer.isRecognitionAvailable() && queryIntentServices(RecognitionService)`。

国产 ROM（小米 / 华为 / OPPO / vivo 等）常见情况是识别服务确实存在、其他 App 的听写也能用，但该 Service 对第三方 `queryIntentServices` 不可见 → AND 结果为 false → 语音面板直接显示"没有可用引擎"，连尝试的机会都没有。

修复：
- 两个探测改为 **OR**：任一信号成立即允许启动；真正坏掉的服务会在 `start()` 失败并标记 degraded，暴露真实原因。
- 没有任何服务可见、但系统仍能处理 `ACTION_RECOGNIZE_SPEECH` 时，语音面板的无引擎页新增 **"使用系统语音输入"** 按钮：拉起系统识别界面，结果直接写回输入框。这是国产 ROM 上最可靠的本地方案。
- 文件：`speech/SystemSpeechRecognitionEngine.kt`、`speech/SpeechRecognitionManager.kt`、
  `ui/chat/voice/InlineVoiceInputPanel.kt`、`res/values{,-zh}/strings.xml`。

### 2. 朗读被"切换输入"打断
两条独立原因，都修了：

- **当前句丢失**：`suspendForCapture()` 只保留排队中尚未朗读的句子，正在念的那一句被引擎销毁吞掉。切回键盘恢复时从头开始下一句，听感就是"打断且不接续"。现在会记住 `currentUtterance`，暂停时放回队列首位，恢复时重播该句。
- **音频焦点瞬断当成永久丢失**：`ReadAloudAudioFocus` 把 `AUDIOFOCUS_LOSS_TRANSIENT` 和永久 `LOSS` 一样处理 → `stop()` 清空整条队列。切换输入法 / 系统语音键盘 / 来电提示都会触发瞬断，于是整段朗读被彻底终止。现在瞬断只暂停并保留队列（也取消 grace 计时避免释放焦点），收到 `AUDIOFOCUS_GAIN` 后自动续读。
- 文件：`speech/ReadAloudPlayer.kt`、`speech/ReadAloudAudioFocus.kt`。

## 包名

`com.minis.vox`（namespace 仍是 `com.openminis.app`，R 类与源码包名不动），可与官方版共存。
副作用：两版都注册 `minis://` scheme，点 deep link 会弹出选择；数据 / 设置互不相通，首次启动需重新配置模型与 API Key。

## APK

- `builds/minis-vox-1.15-release.apk`（42.8 MB，R8、debug key 签名，`debuggable=false`）
- `builds/minis-vox-1.15-debug.apk`（72 MB，含 DEV_TOOLS，排查用）

安装：不同包名，直接侧载即可；也可用 `adb install -r`。

## 构建环境（ARM64 Alpine PRoot，无 x86 主机）

- SDK platform 36 + build-tools 36；x86_64 的 aapt2 / zipalign / aidl 经 `qemu-x86_64` + Debian glibc 运行（`/opt/android-sdk/build-tools/*/aapt2` 为 wrapper，`-L` 不生效，需显式用 `ld-linux` 启动）。
- NDK r27 的 host 工具全部替换为转发 Alpine clang 19 的 shim（`/opt/ndk-shim/dispatch`），补 `-resource-dir` 指向 NDK clang-18 资源树，并在 sysroot 补空的 `libssp_nonshared.a`。
- CMake 由系统 cmake 3.31 顶替（`/opt/android-sdk/cmake/3.22.1/bin/{cmake,ninja}` 为 wrapper）。
- rclone.aar 由 Go 1.26.10 + gomobile 交叉编译（需 patch gomobile 的 host 架构判定，让其接受 linux/arm64 host）。
- gradle 内存：`-Xmx4096m`（默认 2G 会 OOM 在 Kotlin 后端）。

## 已知限制

- Robolectric 单测在 aarch64 上跑不了（上游限制），非 Robolectric 的语音单测通过。
- 本包未做真机语音回归，建议安装后各自验证：① 语音面板是否出现麦克风并能识别；② 朗读中切键盘再切回，是否接着念原句。
