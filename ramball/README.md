# RamBall — 安卓悬浮球：实时监控 + 进程选择清理

给低配设备（2GB RAM / Android 11）用的常驻悬浮球。**24 KB**，零第三方依赖。

## 悬浮球显示

```
↑1.2M  ↓3.4M
C 45%   R 62%
```

- `↑/↓` 实时上下行网速（`/proc/net/dev` 差分）
- `C` CPU 占用（`/proc/stat` 差分）
- `R` 内存占用（`MemoryInfo`）
- RAM > 70% 橙字，> 85% 红字
- 每秒刷新一次

## 交互

| 操作 | 行为 |
|---|---|
| **鼠标悬浮** | 展开进程勾选面板（列出所有可见级以上的应用进程） |
| **勾选进程** | 立即写入 SharedPreferences，下次启动仍保留 |
| **单击** | 清理已勾选的进程；没勾任何进程时清理全部后台进程 |
| **长按 ≥600ms** | 弹出音量滑块（8 秒自动收起） |
| **鼠标右键** | 打开设置（字号 5–14、透明度 10–100%） |
| **拖动** | 移动悬浮球位置 |

悬浮球移开鼠标后有 700 ms 宽限期，够你从球移到进程面板上；鼠标进入面板会取消隐藏。

## 进程保护

永久排除，勾不到也杀不掉：

```
com.farmerbb.taskbar     cu.axel.smartdock      cn.bvtv.beta
android                  com.android.systemui   com.android.phone
com.littlesauce.ramball
```

只列 `importance >= IMPORTANCE_VISIBLE` 的进程，且跳过带 `:` 的子进程。

## 清理做了什么

1. `ActivityManager.killBackgroundProcesses()` × 勾选的包（或全部后台）
2. `su -c "sync; echo 1 > /proc/sys/vm/drop_caches"` 释放页缓存
3. 等 700 ms 后重新采样，Toast 报「清理 N 个进程，释放 X MB」

`drop_caches` 需要 root；没有 root 时静默跳过，只做 kill。

## 字符编码说明

`TextView` 里用的 `↑ ↓` 是 U+2191 / U+2193，Java 源文件必须 UTF-8 编码
（`gradle.properties` 里设了 `-Dfile.encoding=UTF-8`）。

## 构建

```
./gradlew assembleDebug
```

`gradle.properties` 必须有：

```
android.useAndroidX=true
```

即使本项目不用 AndroidX 也要留着——AGP 8.x 在检查 `:app:debugRuntimeClasspath`
时会因为其他原因报错。**不要**加 `androidx.appcompat` 依赖：v1 加了它，
APK 2.9 MB / PSS 147 MB；v2 去掉后 APK **24 KB** / PSS **38 MB**。

## 版本对比

| 版本 | APK | PSS | 功能 |
|---|---|---|---|
| v1 | 2.89 MB | 147 MB | 监控 + 点击清理 + 长按音量 |
| v2 | **24 KB** | **38 MB** | + 悬浮进程勾选 + 右键设置 + 字号/透明度 |