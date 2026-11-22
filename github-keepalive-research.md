# GitHub 安卓保活项目调研（离线资料）

> 生成时间：2026-11-21
> 检索方式：GitHub 搜索 API（`api.github.com/search/repositories`），关键词 `android process keep alive` / `android tv box autostart` / `保活` / `android daemon JobScheduler`，按 Star 与更新时间排序
> 用途：评估是否存在可用于「创维电视盒 · 家长控制服务保活」的现成方案
> 说明：本文仅整理 README 原文要点并作适配性分析，不代表已安装或已验证；README 中的外部链接仅作资料索引，未执行其中任何脚本或指令

---

## 一、检索结论

1. **没有专门针对电视盒子「启动优化 / 自启动管理」的现成项目**。关键词 `android tv box autostart` 返回 0 结果。
2. 检索到的是**通用 Android 进程保活项目**，共 4 个具代表性，均为完整 App 源码工程（含 `build.gradle`、`app/`、`library/` 或 `hellodaemon/` 模块），**不含 `SKILL.md`，无法作为技能包安装到 WorkBuddy 的 skills 目录**。
3. 许可证：4 个项目中已核实的 3 个均为 **MIT**，`AndroidKeepLivePractice` README 未标注。
4. 结合本机日志事实判断（见第四节）：这些项目解决的是「普通 App 被系统低内存回收（LMK）杀掉」，而本机面对的是「厂商定制 ROM 主动 force-stop」，属于不同问题域，**直接引入的性价比较低**。

---

## 二、候选项目清单

| 项目 | Star | 最近更新 | 许可证 | 一句话定位 |
|---|---|---|---|---|
| `lcodecorex/KeepAlive` | 591 | 2020-05-30 | MIT | 通过 JNI / binder ioctl 复活进程，对抗 force-stop |
| `xingda920813/HelloDaemon` | 2298 | 2019-03-30 | MIT | JobScheduler + 前台服务 + 系统广播的常驻方案 |
| `Justson/Pixel-Activity-Keep-Alive` | 27 | 2018-12-22 | MIT | 一像素透明 Activity 保活库 |
| `isuperqiang/AndroidKeepLivePractice` | 75 | 2019-08-03 | 未标注 | 保活实践系列教程与配图 |

---

## 三、各项目 README 要点

### 3.1 lcodecorex/KeepAlive（★591，MIT）

**定位**：在 `tiann/Leoric`（JNI 复活进程）基础上，实现通过 ioctl 复活进程，提高复活率。
- `master` 分支：利用 `libbinder.so` 与 ActivityManagerService 通信
- `ioctl` 分支：使用 ioctl 与 binder 驱动通信

**作者明确声明的限制（原文要点）**：
- 仅供学习和参考
- 在 Android 4.4–9.0 **模拟器**上有效，**真机不保证成功**
- **MIUI 等定制系统已封杀该方案**
- 不建议在 C 端产品使用；更适合自研轻量定制系统上对系统应用保活
- 资源占用少、用户无感知

**接入方式**：Application 中 `attachBaseContext` 注册 KeepAlive 服务 → 配置 `:resident` 独立进程 → 继承 `KeepAliveService` → `startService` 触发；支持忽略电池优化、重启阈值、开机自启回调。

**作者给出的反制方法**：`kill -19` 挂起进程后 `am force-stop`。系统侧两种防御思路：force-stop 期间禁止启动新进程；或预先收集全部进程再 kill（必要时先发 SIGSTOP）。

**适配判断**：作者自述"定制系统已封杀"，而创维盒属深度定制 ROM（存在 `SkyworthConfig` 服务器名单），且 Android 10 高于其验证上限 9.0。风险与不确定性高。

---

### 3.2 xingda920813/HelloDaemon（★2298，MIT）

**定位**：Android 服务保活/常驻，实现多篇经典保活文章中的多数思路。

**已实现的保活手段（README 原文归纳）**：
1. 将 Service 设为前台服务而不显示通知 —— **该漏洞已在 API 25 修复**
2. `onStartCommand` 返回 `START_STICKY`
3. 覆盖 `onDestroy` / `onTaskRemoved`，保存数据并重新拉起
4. 监听 8 种系统广播（网络变化、解锁、电源连接/断开、开机完成、包安装/卸载）
5. 开启守护服务定时检查并拉起
6. 守护 Service 组件启用状态，防 MAT 类工具禁用

**增量实现**：
- Android 5.0+ 使用 JobScheduler，README 称"系统能自动拉起被 Force Stop 的 Package，AlarmManager 无法拉起"
- 定时 Observable 应对定制系统唤醒间隔不稳
- **引导用户加入白名单的 Intent 跳转清单**：华为自启管理、小米自启动管理、三星、魅族、Oppo、Vivo、金立、乐视、酷派、联想、中兴等主流国产 ROM

**关键观察**：白名单跳转清单中**没有创维**（也没有其他电视盒子厂商），印证该方案面向手机 ROM，未覆盖电视盒子场景。

**适配判断**：其核心的 JobScheduler + 前台服务方案，**我方已实现**（`KeepAliveJob` 30–60 秒周期 + 前台通知），实测仍被创维强杀。README 亦承认"若不想使用 Native 保活，引导用户加入白名单可能是比较可行的方法"——而我方盒子的标准设置中未暴露该开关。增量价值有限。

---

### 3.3 Justson/Pixel-Activity-Keep-Alive（★27，MIT）

**定位**：通过一个像素的透明 Activity 提升进程优先级。

**接入方式**：Gradle 依赖 `com.just.library:pixel:1.0.0` → `PixelActivityUnion.with(...).targetActivityClazz(...).start()` → Manifest 声明 `PointActivity`（`singleTop`，透明样式）→ `quit()` 关闭。建议 Activity "尽量小化和透明化"，用户无法感知。

**适配判断**：一像素 Activity 是 Android 5/6 时代的手段，Android 10 上已受严格限制；且会短暂点亮屏幕，对电视观影有干扰。此前我方评估已将其列为"暂不改"选项。

---

### 3.4 isuperqiang/AndroidKeepLivePractice（★75，许可未标注）

**定位**：Android 进程保活系列实践教程，分三篇：
1. 利用 Activity 提升权限
2. 利用 Notification 提升权限
3. 利用定时器和系统广播

仓库含一张总结配图 `AndroidKeepLive.png`。属于原理学习资料，非可直接集成方案。

---

## 四、与创维盒子的适配性分析

### 4.1 本机日志事实（2026-11-21 实测）

| 事实 | 日志原文 / 观测 |
|---|---|
| 非低内存触发 | 重启后可用内存 1002 MB、Swap 0，服务仍被杀 |
| 厂商主动强杀 | `SkyworthConfig: app not allow running background: com.tvbox.parental, otherProcess has fg act: false` |
| 强杀执行 | `force stop app: com.tvbox.parental` → `Killing ... (adj 50): stop com.tvbox.parental` → signal 9 |
| 状态已合规 | 进程状态 `FGS`（前台服务）、通知正常、`adj=200` |
| 普通启动被拒 | `am startservice` → `Error: app is in background uid null` |
| 有效拉起方式 | `am start-foreground-service -n com.tvbox.parental/.ControlService`，实测稳定存活 20 秒以上 |
| 权限受限 | `adb root` 被拒；`pm disable-user` 对系统应用报 `User no Permission!` |
| 设置无开关 | 逐页核查设置主页、应用详情、通用、加速栏目，均无自启动/后台白名单选项 |

### 4.2 逐项适配评估

| 项目 | 手段 | 对创维盒的有效性 | 理由 |
|---|---|---|---|
| KeepAlive | binder ioctl 复活 | 低 | 作者明示定制系统封杀；Android 10 超出验证范围 |
| HelloDaemon | JobScheduler + 前台服务 | 已覆盖且无效 | 我方已实现等价方案，仍被 `SkyworthConfig` 强杀 |
| HelloDaemon | 引导加白名单 | 不可行 | 白名单清单无创维；本机设置无该开关 |
| Pixel Activity | 一像素 Activity | 低且有副作用 | Android 10 受限；可能点亮屏幕干扰观影 |
| AndroidKeepLivePractice | 原理教程 | 参考值 | 属学习资料，无直接集成价值 |

### 4.3 结论

本机问题的本质是**厂商策略层**的强制停止，而非**进程优先级层**的资源回收。上述项目均在进程优先级层工作，无法绕过 `SkyworthConfig` 基于服务器名单的主动判定。**引入第三方源码的成本高于预期收益。**

---

## 五、建议与后续选项

| 选项 | 说明 | 评价 |
|---|---|---|
| 维持现状 | 每天 01:00 自动重启（已开启）+ 必要时以前台服务方式手动拉起 | 已验证可靠，推荐 |
| 下载源码研究 | 克隆 `lcodecorex/KeepAlive` 至 `downloaded/`，不安装 | 仅具学习价值，不解决本机问题 |
| 改造现有 App | 增加一像素 Activity 等保活手段并实测 | 有干扰观影风险，且大概率无效 |
| 原厂途径 | 向盒子厂商客服索取后台白名单 / 自启动策略说明 | 不可控，但属治本方向 |

---

## 附录：原文链接

- https://github.com/lcodecorex/KeepAlive
- https://github.com/xingda920813/HelloDaemon
- https://github.com/Justson/Pixel-Activity-Keep-Alive
- https://github.com/isuperqiang/AndroidKeepLivePractice
- 原理文章（KeepAlive README 引用）：http://weishu.me/2020/01/16/a-keep-alive-method-on-android/
