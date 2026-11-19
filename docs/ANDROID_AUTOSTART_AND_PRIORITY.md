# Android 开机自启与“最高优先级”

## 1. 先给结论

**普通 Android 应用没有官方接口可以把自己设置成系统级“最高优先级”。**

常见误解要逐条排除：

- `android:priority` 只影响 Intent 匹配的优先级，不影响进程调度、CPU、内存或播放器优先级。
- `Thread.setPriority`/`Process.setThreadPriority` 只影响本 App 内部线程，不能压过系统或其他应用。
- `android:persistent="true"` 只对系统签名/预装应用有意义，普通 APK 不应使用，也不要通过它宣称常驻。
- 自启动、后台播放和前台服务都受 Android 版本、厂商 ROM、电池策略和用户授权影响。
- Device Owner/Lock Task 是专用设备管理能力，不是普通用户安装后自动获得的权限。

## 2. 本项目的实现层级

| 层级 | 实现 | 适用范围 | 保证程度 |
|---|---|---|---|
| 普通前台播放 | `mediaPlayback` 前台服务 + 通知 | 手机/TV | 正常应用最高常驻级别；MediaSession 待补 |
| 开机广播 | `RECEIVE_BOOT_COMPLETED` + `BootReceiver` | 手机/TV | 尽力而为，受后台限制 |
| TV 启动器 | `LEANBACK_LAUNCHER` | Android TV | 用户从 TV 桌面启动 |
| Kiosk 默认桌面 | `kiosk` flavor 的 HOME activity-alias | 专用 TV | 用户选择默认桌面后开机进入 |
| Device Owner | 设备所有者 + 持久默认 HOME + Lock Task | 企业/专用设备 | 需预配置/重置设备 |
| OEM 白名单 | 厂商系统签名/白名单 | 厂商合作 | 普通开发者无法自行获得 |

### 2.1 前台播放服务

Manifest：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".playback.PlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback"
    android:stopWithTask="false" />
```

服务启动后必须尽快 `startForeground()`，并提供可见通知。播放器释放、服务销毁和用户停止操作都要清理通知。当前实现没有接入 `android.media.session.MediaSession`，因此电视媒体键和系统媒体控制尚不完整；这部分属于后续增强。

这不是“最高优先级”，但它是 Android 对正常媒体应用推荐的常驻播放方式。

### 2.2 BootReceiver

Manifest：

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver android:name=".boot.BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

项目策略：

1. 读取 `boot_autostart` 开关，默认开启；
2. 尝试启动 `PhoneMainActivity`/`TvMainActivity`；
3. 如果操作系统拒绝后台 Activity，则尝试启动 `mediaPlayback` 前台服务；
4. 如果前台服务也被限制，则显示通知，请用户点击打开；
5. 所有失败路径都只做提示，不循环重试、不刷屏。

关键限制：

- Android 10+ 对后台启动 Activity 有严格限制；
- Android 12+ 对从后台启动前台服务有额外限制；
- 不同 Android 版本和 OEM 对 `BOOT_COMPLETED` 启动前台服务也有限制；
- 用户“强行停止”应用后，系统通常会清掉广播状态；
- 电池优化、厂商自启管理、省电模式都可能影响结果。

因此：**不能承诺所有手机在冷启动后无感自动播放。**

### 2.3 Kiosk 默认桌面

专用 TV 最可靠的开机路径不是 `BootReceiver`，而是让应用成为默认 HOME：

```powershell
cd android
.\gradlew.bat :app:assembleKioskRelease
```

`kiosk` flavor 的 Manifest 增加：

```xml
<activity-alias
    android:name=".ui.KioskHomeAlias"
    android:targetActivity=".ui.TvMainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity-alias>
```

然后由用户在系统设置中选择“默认桌面”。安装 APK 不会自动改默认桌面。

适合：

- 酒店电视；
- 展厅电视；
- 门店/会议室专用屏；
- 企业统一下发的 TV 盒子。

不适合：

- 用户日常手机；
- 不允许改变默认桌面的零售电视。

### 2.4 Device Owner / Lock Task（可选，企业）

如果设备是专用且可重置/重新 provisioning，可以把它设为 Device Owner。这个路径可以：

- 设置持久默认 HOME；
- 设置 Lock Task 白名单；
- 在设备所有者授权下进入 Lock Task（Kiosk）；
- 阻止普通用户退出直播应用。

这需要：

- 设备没有已登录账号且处于可 provisioning 状态，通常要恢复出厂；
- 应用包含 `DeviceAdminReceiver`；
- 通过 QR/NFC/ADB/企业 EMM 完成 provisioning；
- 明确的管理员退出流程。

这不是普通 APK 自动获得的能力，也不应通过隐藏设置或漏洞实现。

## 3. 推荐的设备策略

### 手机

- 使用 `standard` 变体；
- 通知权限由用户授予；
- `BootReceiver` 只做尽力尝试；
- 引导用户开启厂商“自启动/后台运行/电池不限制”；
- 播放时使用前台服务和媒体通知；
- 不申请“系统级常驻”或伪装成系统应用。

### Android TV 普通安装

- 使用 `standard` 变体；
- `LEANBACK_LAUNCHER` 进入；
- 用户从 TV 桌面打开；
- 播放保持前台服务；
- 自启由 TV 厂商桌面或用户设置决定。

### Android TV 专用设备

- 使用 `kiosk` 变体；
- 用户/集成商设置默认 HOME；
- 若要防止退出，使用 Device Owner + Lock Task；
- 在出厂镜像中预装/签名是 OEM 合作项，不是本项目普通 APK 能做到的。

## 4. 设备验收矩阵

至少覆盖：

| 设备类型 | 代表 | 必测项 |
|---|---|---|
| Android TV | Sony/小米/TCL/海信/创维 | Leanback 图标、D-pad、WebView、硬解、待机恢复 |
| TV 盒子 | Amlogic/晶晨方案 | 自启、默认 HOME、休眠唤醒、HDMI 切换 |
| 手机 | 小米/华为/荣耀/OPPO/vivo/三星/Pixel | 自启、通知、锁屏、后台、厂商电池策略 |
| 低内存设备 | 1—2 GB RAM | WebView + 播放器内存、后台回收 |
| 网络异常 | 断网/弱网/切换 Wi-Fi/4G | 重连、重新鉴权、用户提示 |

每个设备记录：

- Android/WebView 版本；
- 冷启动是否进入；
- 锁屏/待机后是否继续；
- 被厂商清理后是否恢复；
- 通知是否可见；
- 播放 2 小时内存/温度；
- 4K/8K 是否硬解。

## 5. 不要做的事

- 不要声明 `android:persistent="true"` 来伪装系统级常驻；
- 不要申请系统签名或修改 `system` 分区；
- 不要用无障碍服务模拟点击绕过后台限制；
- 不要循环拉起 Activity/服务对抗系统；
- 不要把 Device Owner/Kiosk 描述成普通安装即可获得；
- 不要把“前台服务”宣传成“系统最高优先级”。

## 6. 本项目对应代码

- 自启：`android/app/src/main/java/com/ysp/live/boot/BootReceiver.kt`
- 前台播放：`android/app/src/main/java/com/ysp/live/playback/PlaybackService.kt`
- TV/手机入口：`android/app/src/main/java/com/ysp/live/ui/`
- Kiosk HOME alias：`android/app/src/kiosk/AndroidManifest.xml`
- 设置说明：`BaseLiveActivity.showSettings()`

