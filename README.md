# TVBox Parental Control（电视盒家长控制）

一个跑在安卓电视盒上的**纯局域网、免密**家长控制系统。手机浏览器打开盒子上的网页即可控制，无需外网、无需 DDNS、无需账号或令牌。

面向家中孩子看电视的场景：到点出题、答对才能继续，防止沉迷，同时保留遥控器等常用操作。

---

## 功能

- **四个页面**：控制台 / 遥控器 / 题库·错题 / 设置（手机浏览器访问）
- **观看时长控制**：单次上限、每日上限、休息时长均可配置；到点自动锁屏
- **答题解锁**：到点弹出题目，答对 2 题继续看；防瞎猜（累计答错 3 次触发递增惩罚）
- **题库**（150 题，面向一年级左右）：
  - 语文：带声调拼音选汉字
  - 英语：看单词选中文意思 / 看中文选英文单词
  - 数学：20 以内加减（难度固定）
  - 图形：看图选英文单词
- **语音读题**：中文 TTS 朗读题干和选项，不认字的孩子也能听题作答
- **找遥控器**：调用厂商蓝牙服务让遥控器蜂鸣 10 秒
- **锁屏天气 + 倒计时**：显示 7 天天气和休息/每日恢复倒计时
- **定时重启**：可按星期配置每天自动重启（清理长期运行后的内存压力）
- **实时状态**：控制台显示网络、音量、流量、内存（读 `/proc/meminfo`）

## 运行环境

- 安卓电视盒（本项目实测于创维/腾讯极光盒子，Android 10，已开启 ADB 网络调试）
- 盒子与手机在同一局域网
- 一台电脑用于首次编译和 ADB 安装

> 说明：本系统依赖厂商固件的部分能力（蓝牙遥控器蜂鸣服务、ADB 媒体键控制）。
> 不同品牌/固件可能需要调整对应的实现。作者实测的固件会在下文说明注意事项。

## 目录结构

```
apk/
  AndroidManifest.xml       应用清单
  src/com/tvbox/parental/   Java 源码（服务、答题、锁屏、语音、天气、保活等）
  assets/
    index.html              控制台主页面
    bank.html               题库/错题页面
    changelog.html          版本记录
    common.js               共享连接状态轮询
    questions.json          题库（150 题，含 4 套选项顺序变体）
    images/                 图形题图片
    audio/                  语音文件（构建/生成，不随源码提交）
build_apk.py                免 Gradle 编译脚本（javac + d8 + aapt2）
gen_assets.py               语音生成（edge-tts）
build_bank_v*.py            题库生成历史脚本（v4 为基础，v5 调整语文）
revert_en_pinyin.py         英语题去拼音/题干朗读处理
regen_*_audio.py            分科目重合成语音
daily_reboot.py             可选的电脑端兜底定时重启
github-keepalive-research.md 保活方案调研（未采用第三方方案）
```

## 构建

构建不需要 Gradle，但需要自行准备一套 Android 命令行工具链，放到 `build/` 目录：

```
build/
  jdk/jdk-<版本>/bin/{javac,java,keytool}.exe
  bt/android-<版本>/{aapt2.exe,zipalign.exe,lib/d8.jar,lib/apksigner.jar}
  platform/android-<版本>/android.jar
```

然后：

```bash
# 1) 生成题库（可选，仓库已带 questions.json）
python build_bank_v4.py
python build_bank_v5.py
python revert_en_pinyin.py

# 2) 生成语音（需要联网，edge-tts；可选，仓库不含音频）
pip install edge-tts
python gen_assets.py

# 3) 编译 APK（首次会自动生成签名密钥）
set SIGNING_PASS=你的签名密码      # Windows
# export SIGNING_PASS=你的签名密码  # Linux/macOS
python build_apk.py
```

产物为根目录的 `TvBoxControl.apk`。签名密钥 `apk/tvbox.keystore` **首次构建后自动生成，切勿提交到仓库**（`.gitignore` 已排除）。

## 安装与使用

```bash
adb connect <盒子IP>:5555
adb install -r TvBoxControl.apk
adb shell am start-foreground-service -n com.tvbox.parental/.ControlService
```

安装后，手机浏览器访问 `http://<盒子IP>:8765`。

首次需在盒子设置里给本应用开启**自启动 / 后台运行 / 关闭电池优化**。部分厂商 ROM（如作者实测的创维）会从服务器下发后台名单强制停止普通后台服务，导致服务被杀、网页暂时打不开；本项目用前台服务 + JobScheduler + 定时重启做兜底，但无法绕过厂商策略，需以实际固件为准。

## 安全与隐私

- 完全局域网，无外网访问、无账号、无令牌，控制接口免密
- 请只在可信的家庭局域网使用；不要把 8765 端口映射到公网
- 签名密钥、密码均不随源码提供，构建者自行生成

## 许可

本项目采用 [MIT License](LICENSE) 开源，仅供个人/家庭学习与使用。
