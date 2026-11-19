package com.ysp.live.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ysp.live.NativeCore
import com.ysp.live.R
import com.ysp.live.auth.AuthStream
import com.ysp.live.auth.AuthWebViewBridge
import com.ysp.live.data.Channel
import com.ysp.live.data.ChannelRepository
import com.ysp.live.playback.PlaybackService

abstract class BaseLiveActivity : Activity() {
    protected abstract val tvMode: Boolean

    private val tag = "YspLive"
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        getSharedPreferences("ysp_live", Context.MODE_PRIVATE)
    }

    private lateinit var videoSurface: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var channelButton: Button
    private lateinit var drawer: LinearLayout
    private lateinit var channelList: ListView
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var webHost: FrameLayout
    private lateinit var authBridge: AuthWebViewBridge

    private var channels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var pendingStream: AuthStream? = null
    private var playback: PlaybackService? = null
    private var bound = false
    private var authStartedForPid: String? = null
    private var lastRecoveryAt = 0L
    private var pendingSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (tvMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        hideSystemBars()
        enableDeviceOwnerKioskIfPossible()

        channels = ChannelRepository.load(this)
        currentChannel = resolveInitialChannel()
        buildUi()
        requestNotificationPermission()

        Log.i(
            tag,
            "频道=${channels.size}，Rust=${NativeCore.version()}，TV=$tvMode"
        )
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bindPlaybackService()
    }

    override fun onStop() {
        playback?.detachSurface()
        playback?.statusListener = null
        playback?.endedListener = null
        if (bound) {
            runCatching { unbindService(serviceConnection) }
            bound = false
        }
        playback = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::authBridge.isInitialized) authBridge.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Android 13+")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) {
            drawer.visibility = View.GONE
            channelButton.requestFocus()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        videoSurface = SurfaceView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    pendingSurface = holder.surface
                    playback?.attachSurface(holder.surface)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    playback?.detachSurface()
                    pendingSurface = null
                }
            })
        }
        root.addView(videoSurface)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundColor(0x88000000.toInt())
            isClickable = false
        }
        topBar.addView(TextView(this).apply {
            text = "YSP Live · 原生播放"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 22f else 17f)
            isFocusable = false
        })
        statusView = TextView(this).apply {
            text = "准备启动"
            setTextColor(0xFFB3E5FC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 15f else 12f)
            isFocusable = false
        }
        topBar.addView(statusView)
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xAA000000.toInt())
        }
        channelButton = controlButton("频道") { toggleDrawer() }
        controls.addView(channelButton, buttonLayoutParams())
        controls.addView(
            controlButton("重新鉴权") {
                authStartedForPid = null
                currentChannel?.let { startAuth(it) }
            },
            buttonLayoutParams(),
        )
        controls.addView(
            controlButton("停止") {
                playback?.stopPlayback()
                setStatus("已停止，可重新选择频道")
            },
            buttonLayoutParams(),
        )
        controls.addView(
            controlButton("设置") { showSettings() },
            buttonLayoutParams(),
        )
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE0B0B0B.toInt())
            visibility = View.GONE
        }
        drawer.addView(TextView(this).apply {
            text = "频道  ·  央视 ${channels.count { it.kind.label == "央视" }}  /  " +
                "卫视 ${channels.count { it.kind.label == "卫视" }}"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 20f else 16f)
            setPadding(dp(20), dp(18), dp(20), dp(12))
            isFocusable = false
        })

        channelAdapter = ChannelAdapter(channels, tvMode)
        channelList = ListView(this).apply {
            adapter = channelAdapter
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, dp(16))
            isFocusable = true
        }
        channelList.onItemClickListener =
            android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                channels.getOrNull(position)?.let { channel ->
                    drawer.visibility = View.GONE
                    channelButton.requestFocus()
                    startAuth(channel)
                }
            }
        drawer.addView(
            channelList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        root.addView(
            drawer,
            FrameLayout.LayoutParams(
                dp(if (tvMode) 430 else 320),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START,
            ),
        )

        webHost = FrameLayout(this).apply {
            isFocusable = false
            isClickable = false
        }
        root.addView(
            webHost,
            FrameLayout.LayoutParams(1, 1, Gravity.END or Gravity.BOTTOM),
        )

        setContentView(root)
        authBridge = AuthWebViewBridge(
            activity = this,
            container = webHost,
            onStream = ::onAuthStream,
            onStatus = ::setStatus,
        )

        currentChannel?.let { selected ->
            channelAdapter.setSelectedPid(selected.pid)
        }
        handler.post { channelButton.requestFocus() }
    }

    private fun bindPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        val started = runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.isSuccess
        if (!started) {
            setStatus("无法启动前台播放服务")
            return
        }
        bound = runCatching {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            setStatus("播放服务绑定失败")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val mediaService = (service as? PlaybackService.LocalBinder)?.service() ?: return
            playback = mediaService
            mediaService.statusListener = ::setStatus
            mediaService.endedListener = ::scheduleRecovery
            pendingSurface?.takeIf { it.isValid }?.let { surface ->
                mediaService.attachSurface(surface)
            }

            val channel = currentChannel ?: resolveInitialChannel().also {
                currentChannel = it
                channelAdapter.setSelectedPid(it.pid)
            }
            channelAdapter.setSelectedPid(channel.pid)

            if (mediaService.hasMedia && mediaService.currentPid == channel.pid) {
                setStatus("正在播放 · ${channel.name}")
                return
            }
            if (!preferences.getBoolean("auto_play", true)) {
                setStatus("已就绪，请选择频道")
                return
            }
            if (authStartedForPid != channel.pid) {
                handler.postDelayed({
                    if (!isFinishing && !isDestroyed && currentChannel?.pid == channel.pid) {
                        startAuth(channel)
                    }
                }, 350)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
            setStatus("播放服务已断开")
        }
    }

    private fun scheduleRecovery() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt < 15_000) return
        lastRecoveryAt = now
        handler.postDelayed({
            if (!isFinishing && !isDestroyed && ::authBridge.isInitialized) {
                authBridge.reload()
            }
        }, 1_500)
    }

    private fun startAuth(channel: Channel) {
        currentChannel = channel
        authStartedForPid = channel.pid
        pendingStream = null
        preferences.edit().putString("last_pid", channel.pid).apply()
        channelAdapter.setSelectedPid(channel.pid)
        playback?.pausePlayback()
        setStatus("正在鉴权 · ${channel.name}")
        authBridge.load(channel.pid)
    }

    private fun onAuthStream(stream: AuthStream) {
        val channel = currentChannel ?: return
        if (!stream.livePid.isNullOrBlank() && stream.livePid != channel.pid) {
            Log.w(tag, "忽略不匹配的 livepid")
            return
        }
        pendingStream = stream
        playback?.let { playPending(it) }
    }

    private fun playPending(service: PlaybackService) {
        val stream = pendingStream ?: return
        val channel = currentChannel ?: return
        pendingStream = null
        service.play(stream.url, channel.name, channel.pid)
        setStatus(
            "原生播放 · ${channel.name}" +
                stream.definition?.let { " · $it" }.orEmpty()
        )
    }

    private fun setStatus(message: String) {
        Log.i(tag, message)
        if (::statusView.isInitialized) {
            statusView.text = message
        }
    }

    private fun resolveInitialChannel(): Channel {
        val lastPid = preferences.getString("last_pid", null)
        return channels.firstOrNull { it.pid == lastPid }
            ?: channels.firstOrNull { it.pid == ChannelRepository.DEFAULT_PID }
            ?: channels.firstOrNull()
            ?: ChannelRepository.fallbackChannels().first()
    }

    private fun toggleDrawer() {
        if (drawer.visibility == View.VISIBLE) {
            drawer.visibility = View.GONE
            channelButton.requestFocus()
            return
        }

        drawer.visibility = View.VISIBLE
        val selected = channels.indexOfFirst { it.pid == currentChannel?.pid }
        if (selected >= 0) {
            channelList.setSelection(selected)
        }
        channelList.post { channelList.requestFocus() }
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val boot = CheckBox(this).apply {
            text = "开机自启（尽力而为）"
            isChecked = preferences.getBoolean("boot_autostart", true)
        }
        val autoPlay = CheckBox(this).apply {
            text = "打开应用后自动播放"
            isChecked = preferences.getBoolean("auto_play", true)
        }
        val explanation = TextView(this).apply {
            text = "Android 没有允许普通应用设置“最高优先级”的接口。\n\n" +
                "本应用使用 mediaPlayback 前台服务和媒体通知；手机厂商可能仍会阻止" +
                "后台启动。Android TV 专用设备可安装 kiosk 变体并设为默认桌面，" +
                "这是普通应用可获得的最可靠开机入口。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(boot)
        content.addView(autoPlay)
        content.addView(explanation)

        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(content)
            .setPositiveButton("保存") { _, _ ->
                preferences.edit()
                    .putBoolean("boot_autostart", boot.isChecked)
                    .putBoolean("auto_play", autoPlay.isChecked)
                    .apply()
                setStatus("设置已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001,
            )
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun enableDeviceOwnerKioskIfPossible() {
        if (!tvMode) return
        val devicePolicyManager = getSystemService(DevicePolicyManager::class.java) ?: return
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) return

        val kioskAlias = ComponentName(packageName, "com.ysp.live.ui.KioskHomeAlias")
        val aliasExists = runCatching {
            packageManager.getActivityInfo(kioskAlias, 0)
        }.isSuccess
        if (!aliasExists) return

        val admin = ComponentName(packageName, "com.ysp.live.kiosk.YspDeviceAdminReceiver")
        runCatching {
            devicePolicyManager.setLockTaskPackages(admin, arrayOf(packageName))
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            devicePolicyManager.addPersistentPreferredActivity(
                admin,
                homeFilter,
                kioskAlias,
            )
            startLockTask()
            Log.i(tag, "Device Owner Kiosk 模式已启用")
        }.onFailure { error ->
            Log.w(tag, "Device Owner Kiosk 启用失败: ${error.javaClass.simpleName}")
        }
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 18f else 14f)
        minWidth = 0
        minHeight = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        backgroundTintList = ColorStateList.valueOf(0xCC1565C0.toInt())
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class ChannelAdapter(
        private val channels: List<Channel>,
        private val tvMode: Boolean,
    ) : BaseAdapter() {
        private var selectedPid: String? = null

        fun setSelectedPid(pid: String?) {
            selectedPid = pid
            notifyDataSetChanged()
        }

        override fun getCount(): Int = channels.size

        override fun getItem(position: Int): Channel = channels[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(parent?.context).apply {
                isFocusable = true
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(20), 0, dp(20), 0)
                background = rowBackground()
            }
            val channel = getItem(position)
            view.text = channel.displayName
            view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (tvMode) 20f else 16f,
            )
            view.isSelected = channel.pid == selectedPid
            view.layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (tvMode) 58 else 48),
            )
            return view
        }

        private fun rowBackground(): StateListDrawable = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                ColorDrawable(0xFF1E88E5.toInt()),
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                ColorDrawable(0xFF0D47A1.toInt()),
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }

        private fun dp(value: Int): Int {
            val density = android.content.res.Resources.getSystem().displayMetrics.density
            return (value * density).toInt()
        }
    }
}

