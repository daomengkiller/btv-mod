# btv-mod — BTV 手势与性能修补

BTV = [hamenot/BTV](https://github.com/hamenot/BTV)，哔哩哔哩第三方 Android TV 客户端的个人再修改版。
本仓库记录在 W603E-MH（中兴云电脑，RK3568 / Android 11 / 2GB RAM）上做的两处修改。

## 补丁

| 文件 | 内容 |
|---|---|
| `v1-volume-throttle.patch` | 音量手势的 AudioService IPC 节流 |
| `v2-volume-accumulate.patch` | 音量手势的位移累积（**真正解决卡顿的那个**） |

## 问题现象

播放视频时，右手按住屏幕右侧上下滑动调音量，手感顿挫、跳档；左手调亮度则顺滑。

## 根因

### 1. 位移计算错误（主因）

`PlayerGestureHandler.kt` 里 `adjustVolume()`：

```kotlin
val deltaSteps = (deltaY / 80f).toInt()
```

`deltaY` 是**每帧位移**，不是累计位移。鼠标/手指拖拽时单帧位移通常 10–50 px，
除以 80 再取整几乎恒为 0 —— 音量不变；直到某帧恰好超过 80 px 才跳一档。
表现就是"划半天不动然后猛跳一格"。

亮度走的是浮点连续映射（`currentBrightness + deltaY / 1000f`），每帧都有变化，所以顺滑。

**修复**：加累积器，并把不足一档的余量回填。

```kotlin
accumulator += deltaY
val deltaSteps = (accumulator / 40f).toInt()
if (deltaSteps == 0) return lastPercent
accumulator -= deltaSteps * 40f
```

步长同时从 80 px 降到 40 px，更跟手。

### 2. 无效 IPC（次因）

原实现每个 pointer event（60–120 Hz）都调用三次 AudioService：
`getStreamMaxVolume` + `getStreamVolume` + `setStreamVolume`。
即使算出的新音量等于当前音量，`setStreamVolume` 照样执行。

**修复**：缓存 `maxVolume`；`deltaSteps == 0` 时直接返回不发 IPC；`newValue != current` 才写。

## 应用

```bash
git clone --depth 1 https://github.com/hamenot/BTV.git
cd BTV
git am /path/to/v1-volume-throttle.patch
git am /path/to/v2-volume-accumulate.patch
```

同时需要注意两个构建配置（真机安装必须改）：

```kotlin
// app/build.gradle.kts
// 1. debug 通道默认只打 x86_64（给模拟器用）
ndk { abiFilters += "arm64-v8a" }

// 2. debug 包名后缀会导致与原版共存，配置不继承
// applicationIdSuffix = ".debug"
```

## 设备侧观察（同一台机器，作为对照数据）

修复前后系统内存状况：

| 指标 | 优化前 | 优化后 |
|---|---|---|
| `vm.swappiness` | 100 | 60 |
| 空闲内存 | 64 MB | 304 MB |
| Swap 已用 | 724 MB | 593 MB |
| CPU idle（共 400%） | 150% | 254% |

`dumpsys gfxinfo cn.bvtv.beta` 修复前：68,182 帧里 72.88% 是 janky 帧，
50th percentile 19 ms（已超 16.6 ms 的 60fps 预算），99th 73 ms。

`kill -3` 抓的 trace 显示：25 次 GC 共 9.217 秒，平均每次停顿 368 ms；
`JNI: CheckJNI is on`（debug 构建强制开启，只有 release 版能关）。