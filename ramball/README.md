# RamBall — 安卓悬浮球内存清理 + 实时监控

给 W603E-MH（2GB RAM / Android 11）这类低配设备用的常驻悬浮球。

## 功能

**常驻显示**（每秒刷新）：

```
↑1.2M  ↓3.4M
C 45%   R 62%
```

- `↑/↓` 上下行实时网速（读 `/proc/net/dev` 差分）
- `C` CPU 占用百分比（读 `/proc/stat` 差分）
- `R` 内存占用百分比（`ActivityManager.MemoryInfo`）
- RAM 超 70% 变橙色，超 85% 变红色

**操作**：

| 手势 | 行为 |
|---|---|
| 单击 | 清理内存（`killBackgroundProcesses` + `drop_caches`），Toast 显示释放量 |
| 长按 ≥600ms | 弹出音量滑块面板（8 秒后自动消失） |
| 拖动 | 移动位置 |

## 保护名单

以下进程不会被误杀：

```
com.farmerbb.taskbar    cu.axel.smartdock    cn.bvtv.beta
android                 com.android.systemui  com.android.phone
com.littlesauce.ramball
```

## 权限

- `SYSTEM_ALERT_WINDOW`（悬浮窗，必须手动授予）
- `FOREGROUND_SERVICE`（前台服务保活）
- `KILL_BACKGROUND_PROCESSES`

`drop_caches` 需要 root，通过 `su -c` 调用；没有 root 时只是跳过这一步。

## 安装

```bash
adb install -r -g RamBall.apk
adb shell appops set com.littlesauce.ramball SYSTEM_ALERT_WINDOW allow
adb shell am start-foreground-service -n com.littlesauce.ramball/.RamBallService
```

或打开 app 按三个按钮走一遍。

## 构建

```bash
# 需要 gradle.properties 里有 android.useAndroidX=true
./gradlew assembleDebug
```

注意 `gradle.properties` 必须包含：

```
android.useAndroidX=true
```

否则 appcompat 依赖会报 `Configuration contains AndroidX dependencies`。

## 已知问题

- 进程 PSS 约 147 MB（appcompat 依赖 + 每秒刷新）。如果嫌大，可以去掉
  `androidx.appcompat` 依赖（本项目实际只用到 `Activity` 和 `Settings`，
  不需要 appcompat），PSS 能降到 40 MB 以内。
- 悬浮球默认在右上角 (16, 200)，可以拖到任意位置。