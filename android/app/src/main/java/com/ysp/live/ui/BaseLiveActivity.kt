package com.ysp.live.ui

import android.Manifest
import android.app.Activity
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
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ysp.live.NativeCore
import com.ysp.live.R
import com.ysp.live.auth.AuthStream
import com.ysp.live.auth.AuthWebViewBridge
import com.ysp.live.auth.CctvLiveFallback
import com.ysp.live.data.Channel
import com.ysp.live.data.ChannelRepository
import com.ysp.live.data.EpgProgram
import com.ysp.live.data.EpgRepository
import com.ysp.live.playback.PlaybackService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

abstract class BaseLiveActivity : Activity() {
    protected abstract val tvMode: Boolean

    private enum class PlaybackUiState {
        IDLE,
        CONNECTING,
        BUFFERING,
        PLAYING,
        STOPPED,
        ERROR,
    }

    private val tag = "YspLive"
    private val handler = Handler(Looper.getMainLooper())
    private val drawerAutoHideRunnable = Runnable { hideChannelDrawer() }
    private val guideLoadRunnable = Runnable {
        pendingGuideChannel?.let { channel ->
            loadGuide(channel, guideLoadGeneration)
        }
    }
    private val cctvFallbackRunnable = Runnable { activateCctvFallback() }
    private val preferences by lazy {
        getSharedPreferences("ysp_live", Context.MODE_PRIVATE)
    }

    private lateinit var videoView: View
    private lateinit var statusView: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerSeparator: View
    private lateinit var guidePane: LinearLayout
    private lateinit var channelList: ListView
    private lateinit var channelAdapter: ChannelAdapter
    private var channelListHeaderCount = 0
    private lateinit var guideTitle: TextView
    private lateinit var guideSubtitle: TextView
    private lateinit var guideStatus: TextView
    private lateinit var guideList: ListView
    private lateinit var guideAdapter: EpgAdapter
    private lateinit var webHost: FrameLayout
    private lateinit var authBridge: AuthWebViewBridge

    private var channels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var pendingStream: AuthStream? = null
    private var playback: PlaybackService? = null
    private var bound = false
    private var authStartedForPid: String? = null
    private var officialPlayerActive = false
    private var lastRecoveryAt = 0L
    private var lastStatusMessage = ""
    private var playbackUiState = PlaybackUiState.IDLE
    private var bufferingOverlayRunnable: Runnable? = null
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth = 0
    private var pendingSurfaceHeight = 0
    private var ownsPendingSurface = false
    private var touchTracking = false
    private var touchMoved = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownPosition = -1
    private var touchStartedOutsideDrawer = false
    private var consumeDrawerToggleUp = false
    private var lastChannelPickPid: String? = null
    private var lastChannelPickAt = 0L
    private var pendingGuideChannel: Channel? = null
    private var guideLoadGeneration = 0
    private var fallbackActive = false
    private var fallbackAttemptedForPid: String? = null
    private var fallbackScheduledForPid: String? = null
    private var drawerFullWidth = 0
    private var drawerChannelWidth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = if (tvMode) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        enableDeviceOwnerKioskIfPossible()

        channels = ChannelRepository.load(this)
        currentChannel = resolveInitialChannel()
        buildUi()
        hideSystemBars()
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
            hideChannelDrawer()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::drawer.isInitialized) return super.dispatchKeyEvent(event)
        if (consumeDrawerToggleUp && isChannelToggleKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                consumeDrawerToggleUp = false
            }
            return true
        }
        if (isChannelToggleKey(event.keyCode)) {
            if (drawer.visibility == View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    consumeDrawerToggleUp = true
                    if (!guideList.hasFocus()) {
                        val position = channelIndexFromListPosition(
                            channelList.selectedItemPosition
                        )
                        if (position >= 0) {
                            selectChannel(channelAdapter.getItem(position))
                        }
                    }
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    consumeDrawerToggleUp = true
                    showChannelDrawer()
                }
                return true
            }
        }
        if (drawer.visibility == View.VISIBLE) {
            resetDrawerAutoHide()
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!guideList.hasFocus()) {
                            moveChannelSelection(-1)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!guideList.hasFocus()) {
                            moveChannelSelection(1)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (guideList.visibility == View.VISIBLE) {
                            guideList.requestFocus()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (guideList.hasFocus()) {
                            channelList.requestFocus()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::drawer.isInitialized) return super.dispatchTouchEvent(event)
        if (drawer.visibility == View.VISIBLE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetDrawerAutoHide()
                    touchTracking = true
                    touchMoved = false
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    touchDownPosition = channelPositionAt(event)
                    touchStartedOutsideDrawer = !isInsideDrawer(event)
                    if (touchStartedOutsideDrawer) return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    touchMoved = true
                    if (touchStartedOutsideDrawer) return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchTracking && !touchMoved) {
                        val dx = event.rawX - touchDownX
                        val dy = event.rawY - touchDownY
                        touchMoved = (dx * dx + dy * dy) >
                            dp(TAP_SLOP_DP) * dp(TAP_SLOP_DP)
                    }
                    if (touchStartedOutsideDrawer) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val wasOutside = touchStartedOutsideDrawer
                    touchTracking = false
                    touchMoved = false
                    touchDownPosition = -1
                    touchStartedOutsideDrawer = false
                    if (wasOutside) return true
                }
                MotionEvent.ACTION_UP -> {
                    val position = touchDownPosition
                    val upPosition = channelPositionAt(event)
                    val wasOutside = touchStartedOutsideDrawer
                    val shouldSelect =
                        touchTracking &&
                            !touchMoved &&
                            position >= 0 &&
                            position == upPosition
                    val shouldDismiss =
                        wasOutside &&
                            touchTracking &&
                            !touchMoved &&
                            !isInsideDrawer(event)
                    touchTracking = false
                    touchMoved = false
                    touchDownPosition = -1
                    touchStartedOutsideDrawer = false
                    if (wasOutside) {
                        if (shouldDismiss) dismissDrawerStep()
                        return true
                    }
                    val handled = super.dispatchTouchEvent(event)
                    if (shouldSelect) {
                        selectChannel(channelAdapter.getItem(position))
                    }
                    return handled
                }
            }
            return super.dispatchTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTracking = true
                touchMoved = false
                touchDownX = event.rawX
                touchDownY = event.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN -> touchMoved = true
            MotionEvent.ACTION_MOVE -> {
                if (touchTracking && !touchMoved) {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    touchMoved = (dx * dx + dy * dy) >
                        dp(TAP_SLOP_DP) * dp(TAP_SLOP_DP)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touchTracking = false
                touchMoved = false
            }
            MotionEvent.ACTION_UP -> {
                val shouldShow = touchTracking && !touchMoved
                touchTracking = false
                touchMoved = false
                val handled = super.dispatchTouchEvent(event)
                if (shouldShow) showChannelDrawer()
                return handled
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInsideDrawer(event: MotionEvent): Boolean {
        if (!::drawer.isInitialized || drawer.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        drawer.getLocationOnScreen(location)
        return event.rawX >= location[0] &&
            event.rawX < location[0] + drawer.width &&
            event.rawY >= location[1] &&
            event.rawY < location[1] + drawer.height
    }

    private fun channelPositionAt(event: MotionEvent): Int {
        if (!::channelList.isInitialized) return -1
        val location = IntArray(2)
        channelList.getLocationOnScreen(location)
        val x = (event.rawX - location[0]).toInt()
        val y = (event.rawY - location[1]).toInt()
        if (x !in 0 until channelList.width || y !in 0 until channelList.height) {
            return -1
        }
        return channelIndexFromListPosition(channelList.pointToPosition(x, y))
    }

    private fun channelIndexFromListPosition(position: Int): Int {
        val index = position - channelListHeaderCount
        return if (index in channels.indices) index else -1
    }

    private fun listPositionForChannel(position: Int): Int =
        position + channelListHeaderCount

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val videoStage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }
        content.addView(
            videoStage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        videoView = createVideoView()
        videoStage.addView(videoView)

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0B0B.toInt())
            isClickable = true
            isFocusable = false
            visibility = View.GONE
        }
        val loadingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        loadingIndicator = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(0xFF42A5F5.toInt())
        }
        loadingContent.addView(
            loadingIndicator,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        statusView = TextView(this).apply {
            setTextColor(0xFFE8F2FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 18f else 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setMaxWidth(dp(if (tvMode) 700 else 520))
            gravity = Gravity.CENTER
            isFocusable = false
            isClickable = false
        }
        loadingContent.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
        loadingOverlay.addView(
            loadingContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        videoStage.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF20B0B0B.toInt())
            visibility = View.GONE
        }

        val screenHeight = minOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        val channelCenterInset = (
            (screenHeight - dp(if (tvMode) 58 else 48)) / 2
            ).coerceAtLeast(0)
        channelAdapter = ChannelAdapter(channels, tvMode, ::selectChannel)
        channelList = ListView(this).apply {
            addHeaderView(
                View(this@BaseLiveActivity).apply {
                    isFocusable = false
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        channelCenterInset,
                    )
                },
                null,
                false,
            )
            addFooterView(
                View(this@BaseLiveActivity).apply {
                    isFocusable = false
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        channelCenterInset,
                    )
                },
                null,
                false,
            )
            adapter = channelAdapter
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, dp(16))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    resetDrawerAutoHide()
                    val channelPosition = channelIndexFromListPosition(position)
                    channels.getOrNull(channelPosition)?.let(::scheduleGuideLoad)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            })
            setOnKeyListener { _, keyCode, event ->
                resetDrawerAutoHide()
                if (
                    event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                    guideList.visibility == View.VISIBLE
                ) {
                    guideList.requestFocus()
                    true
                } else {
                    false
                }
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    resetDrawerAutoHide()
                }

                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int,
                ) = Unit
            })
        }
        channelListHeaderCount = channelList.headerViewsCount

        guideAdapter = EpgAdapter(tvMode)
        guideTitle = TextView(this).apply {
            text = "节目单 · 今天"
            setTextColor(0xFF90CAF9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 16f else 13f)
            isFocusable = false
        }
        guideSubtitle = TextView(this).apply {
            text = currentChannel?.name.orEmpty()
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 22f else 18f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = false
        }
        guideStatus = TextView(this).apply {
            text = "正在加载节目单…"
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (tvMode) 17f else 14f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding(dp(8), dp(24), dp(8), dp(24))
            setOnClickListener {
                val channel = pendingGuideChannel ?: return@setOnClickListener
                guideLoadGeneration += 1
                showGuideLoading(channel)
                loadGuide(channel, guideLoadGeneration)
            }
        }
        guideList = ListView(this).apply {
            adapter = guideAdapter
            divider = ColorDrawable(0x18FFFFFF)
            dividerHeight = dp(1)
            setBackgroundColor(Color.TRANSPARENT)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(6), 0, dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE
            setOnKeyListener { _, keyCode, event ->
                resetDrawerAutoHide()
                if (
                    event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                ) {
                    channelList.requestFocus()
                    true
                } else {
                    false
                }
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    resetDrawerAutoHide()
                }

                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int,
                ) = Unit
            })
        }

        guidePane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2161616.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        guidePane.addView(
            guideTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        guidePane.addView(
            guideSubtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(3) },
        )
        guidePane.addView(
            guideStatus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        guidePane.addView(
            guideList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val screenWidth = maxOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        drawerFullWidth = (
            screenWidth * if (tvMode) 0.74f else 0.80f
            ).toInt()
        drawerChannelWidth = (
            drawerFullWidth * if (tvMode) 0.42f else 0.36f
            ).toInt()
        drawer.addView(
            channelList,
            LinearLayout.LayoutParams(
                drawerChannelWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        drawerSeparator = View(this).apply { setBackgroundColor(0x33FFFFFF) }
        drawer.addView(
            drawerSeparator,
            LinearLayout.LayoutParams(
                dp(1),
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        drawer.addView(
            guidePane,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        videoStage.addView(
            drawer,
            FrameLayout.LayoutParams(
                drawerFullWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START,
            ),
        )

        webHost = FrameLayout(this).apply {
            isFocusable = false
            isClickable = false
            clipChildren = true
        }
        videoStage.addView(
            webHost,
            FrameLayout.LayoutParams(1, 1, Gravity.END or Gravity.BOTTOM),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            content.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        authBridge = AuthWebViewBridge(
            activity = this,
            container = webHost,
            onStream = ::onAuthStream,
            onStatus = ::setStatus,
        )

        currentChannel?.let { selected ->
            channelAdapter.setSelectedPid(selected.pid)
        }
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

    private fun createVideoView(): View {
        if (tvMode) {
            return SurfaceView(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        attachVideoSurface(
                            holder.surface,
                            holder.surfaceFrame.width(),
                            holder.surfaceFrame.height(),
                            owned = false,
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        if (width <= 0 || height <= 0) return
                        pendingSurfaceWidth = width
                        pendingSurfaceHeight = height
                        playback?.resizeSurface(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        detachVideoSurface()
                    }
                })
            }
        }

        return TextureView(this).apply {
            isOpaque = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    val surface = Surface(texture)
                    attachVideoSurface(surface, width, height, owned = true)
                }

                override fun onSurfaceTextureSizeChanged(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    if (width <= 0 || height <= 0) return
                    pendingSurfaceWidth = width
                    pendingSurfaceHeight = height
                    playback?.resizeSurface(width, height)
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    detachVideoSurface()
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
    }

    private fun attachVideoSurface(
        surface: Surface,
        width: Int,
        height: Int,
        owned: Boolean,
    ) {
        if (pendingSurface !== surface) {
            releasePendingSurface()
        }
        pendingSurface = surface
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height
        ownsPendingSurface = owned
        playback?.attachSurface(surface, width, height)
    }

    private fun detachVideoSurface() {
        playback?.detachSurface()
        releasePendingSurface()
    }

    private fun releasePendingSurface() {
        val surface = pendingSurface
        pendingSurface = null
        pendingSurfaceWidth = 0
        pendingSurfaceHeight = 0
        if (ownsPendingSurface) {
            runCatching { surface?.release() }
        }
        ownsPendingSurface = false
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val mediaService = (service as? PlaybackService.LocalBinder)?.service() ?: return
            playback = mediaService
            mediaService.statusListener = ::setStatus
            mediaService.endedListener = ::scheduleRecovery
            pendingSurface?.takeIf { it.isValid }?.let { surface ->
                mediaService.attachSurface(
                    surface,
                    pendingSurfaceWidth,
                    pendingSurfaceHeight,
                )
            }

            val channel = currentChannel ?: resolveInitialChannel().also {
                currentChannel = it
                channelAdapter.setSelectedPid(it.pid)
            }
            channelAdapter.setSelectedPid(channel.pid)

            if (
                ChannelRepository.supportsNativePlayback(channel) &&
                mediaService.hasMedia &&
                mediaService.currentPid == channel.pid
            ) {
                setStatus("正在播放 · ${channel.name}")
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
        if (fallbackActive) return
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
        cancelCctvFallback()
        fallbackActive = false
        fallbackAttemptedForPid = null
        currentChannel = channel
        authStartedForPid = channel.pid
        pendingStream = null
        playbackUiState = PlaybackUiState.CONNECTING
        cancelBufferingOverlay()
        preferences.edit().putString("last_pid", channel.pid).apply()
        channelAdapter.setSelectedPid(channel.pid)
        showLoadingOverlay("正在切换 · ${channel.name}")
        officialPlayerActive = !ChannelRepository.supportsNativePlayback(channel)
        if (officialPlayerActive) {
            playback?.stopPlayback()
        } else {
            playback?.pausePlayback()
        }
        setOfficialPlayerMode(officialPlayerActive)
        setStatus("正在鉴权 · ${channel.name}")
        authBridge.load(channel.pid)
        scheduleCctvFallback(channel)
    }

    private fun scheduleCctvFallback(channel: Channel) {
        if (CctvLiveFallback.urlFor(channel) == null) return
        fallbackScheduledForPid = channel.pid
        handler.postDelayed(cctvFallbackRunnable, CCTV_FALLBACK_TIMEOUT_MS)
    }

    private fun requestCctvFallbackForStatus(message: String): Boolean {
        if (fallbackActive || !isCctvPrimaryFailure(message)) return false
        val channel = currentChannel ?: return false
        if (fallbackAttemptedForPid == channel.pid) return false
        if (CctvLiveFallback.urlFor(channel) == null) return false
        handler.removeCallbacks(cctvFallbackRunnable)
        fallbackScheduledForPid = channel.pid
        handler.post(cctvFallbackRunnable)
        return true
    }

    private fun isCctvPrimaryFailure(message: String): Boolean =
        message.startsWith("鉴权页加载失败") ||
            message.startsWith("官方播放器返回了不受支持的播放地址")

    private fun activateCctvFallback() {
        val channel = currentChannel ?: return
        val scheduledPid = fallbackScheduledForPid ?: return
        if (scheduledPid != channel.pid || isFinishing || isDestroyed) return
        fallbackScheduledForPid = null
        if (
            fallbackActive ||
            fallbackAttemptedForPid == channel.pid ||
            playbackUiState == PlaybackUiState.PLAYING
        ) {
            return
        }
        val url = CctvLiveFallback.urlFor(channel) ?: return
        fallbackAttemptedForPid = channel.pid
        fallbackActive = true
        pendingStream = null
        officialPlayerActive = true
        playbackUiState = PlaybackUiState.CONNECTING
        cancelBufferingOverlay()
        showLoadingOverlay("备选线路 · ${channel.name}", showProgress = true)
        playback?.stopPlayback()
        setOfficialPlayerMode(true)
        setStatus("正在加载央视备选线路 · ${channel.name}")
        authBridge.loadCctvFallback(channel.pid, url)
    }

    private fun cancelCctvFallback() {
        handler.removeCallbacks(cctvFallbackRunnable)
        fallbackScheduledForPid = null
    }

    private fun onAuthStream(stream: AuthStream) {
        if (fallbackActive) return
        val channel = currentChannel ?: return
        if (!stream.livePid.isNullOrBlank() && stream.livePid != channel.pid) {
            Log.w(tag, "忽略不匹配的 livepid")
            return
        }
        if (officialPlayerActive) {
            setStatus("正在连接 · ${channel.name}")
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
        if (message == lastStatusMessage) return
        lastStatusMessage = message
        Log.i(tag, message)
        val channelName = currentChannel?.name.orEmpty()
        if (message.startsWith("正在播放 ·")) {
            cancelCctvFallback()
        }
        if (requestCctvFallbackForStatus(message)) {
            return
        }
        when {
            message.startsWith("正在播放 ·") -> {
                cancelBufferingOverlay()
                playbackUiState = PlaybackUiState.PLAYING
                hideLoadingOverlay()
            }
            message.startsWith("正在鉴权") || message.startsWith("正在切换") ->
                showLoadingOverlay("正在切换 · $channelName")
            message.startsWith("官方鉴权页已加载") ||
                message.startsWith("央视备选页已加载") ||
                message.startsWith("正在连接") ||
                message.startsWith("官方播放器已取流") -> {
                if (playbackUiState == PlaybackUiState.CONNECTING) {
                    showLoadingOverlay("正在连接 · $channelName")
                }
            }
            message.contains("缓冲") -> {
                if (playbackUiState == PlaybackUiState.CONNECTING ||
                    playbackUiState == PlaybackUiState.PLAYING ||
                    playbackUiState == PlaybackUiState.BUFFERING
                ) {
                    playbackUiState = PlaybackUiState.BUFFERING
                    scheduleBufferingOverlay(channelName)
                }
            }
            message.startsWith("播放异常") -> {
                cancelBufferingOverlay()
                playbackUiState = PlaybackUiState.CONNECTING
                showLoadingOverlay("播放异常，正在重新连接", showProgress = true)
            }
            message.startsWith("已停止") -> {
                cancelBufferingOverlay()
                playbackUiState = PlaybackUiState.STOPPED
                showLoadingOverlay("已停止，可选择频道重新播放", showProgress = false)
            }
            message.startsWith("播放已停止") || message.startsWith("播放结束") -> {
                if (playbackUiState != PlaybackUiState.CONNECTING) {
                    cancelBufferingOverlay()
                    playbackUiState = PlaybackUiState.STOPPED
                    showLoadingOverlay("已停止，可选择频道重新播放", showProgress = false)
                }
            }
            message.startsWith("鉴权页错误") -> Log.w(tag, "忽略网页内部脚本错误")
            message.contains("错误") ||
                message.contains("失败") ||
                message.contains("无法") ||
                message.contains("断开") ||
                message.contains("不受支持") -> {
                cancelBufferingOverlay()
                playbackUiState = PlaybackUiState.ERROR
                showLoadingOverlay("播放失败，请重新选择频道", showProgress = false)
            }
        }
    }

    private fun scheduleBufferingOverlay(channelName: String) {
        cancelBufferingOverlay()
        if (loadingOverlay.visibility == View.VISIBLE) {
            showLoadingOverlay("正在缓冲 · $channelName")
            return
        }
        val pid = currentChannel?.pid
        val runnable = Runnable {
            bufferingOverlayRunnable = null
            if (
                playbackUiState == PlaybackUiState.BUFFERING &&
                currentChannel?.pid == pid
            ) {
                showLoadingOverlay("正在缓冲 · $channelName")
            }
        }
        bufferingOverlayRunnable = runnable
        handler.postDelayed(runnable, 600)
    }

    private fun cancelBufferingOverlay() {
        bufferingOverlayRunnable?.let(handler::removeCallbacks)
        bufferingOverlayRunnable = null
    }

    private fun showLoadingOverlay(message: String, showProgress: Boolean = true) {
        if (!::loadingOverlay.isInitialized) return
        statusView.text = message
        loadingIndicator.visibility = if (showProgress) View.VISIBLE else View.GONE
        loadingOverlay.visibility = View.VISIBLE
        if (!::drawer.isInitialized || drawer.visibility != View.VISIBLE) {
            loadingOverlay.bringToFront()
        }
    }

    private fun hideLoadingOverlay() {
        if (::loadingOverlay.isInitialized) {
            loadingOverlay.visibility = View.GONE
        }
    }

    private fun setOfficialPlayerMode(enabled: Boolean) {
        webHost.layoutParams = if (enabled) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        } else {
            FrameLayout.LayoutParams(1, 1, Gravity.END or Gravity.BOTTOM)
        }
        webHost.isFocusable = enabled
        webHost.isClickable = enabled
        webHost.visibility = View.VISIBLE
        if (enabled) {
            webHost.bringToFront()
        }
        if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) {
            drawer.bringToFront()
        } else if (
            ::loadingOverlay.isInitialized &&
            loadingOverlay.visibility == View.VISIBLE
        ) {
            loadingOverlay.bringToFront()
        }
        if (::authBridge.isInitialized) {
            authBridge.setOfficialPlayerMode(enabled)
        }
    }

    private fun resolveInitialChannel(): Channel {
        val lastPid = preferences.getString("last_pid", null)
        return channels.firstOrNull { it.pid == lastPid }
            ?: channels.firstOrNull { it.pid == ChannelRepository.DEFAULT_PID }
            ?: channels.firstOrNull()
            ?: ChannelRepository.fallbackChannels().first()
    }

    private fun showChannelDrawer() {
        if (!::drawer.isInitialized) return
        if (drawer.visibility == View.VISIBLE) {
            resetDrawerAutoHide()
            return
        }
        setDrawerGuideVisible(true)
        drawer.visibility = View.VISIBLE
        drawer.bringToFront()
        val selected = channels.indexOfFirst { it.pid == currentChannel?.pid }
        if (selected >= 0) {
            channelList.setSelection(listPositionForChannel(selected))
        }
        (channels.getOrNull(selected) ?: currentChannel)?.let(::scheduleGuideLoad)
        channelList.post {
            if (drawer.visibility == View.VISIBLE) {
                channelList.requestFocusFromTouch()
                channelList.requestFocus()
                centerChannelSelection(selected)
            }
        }
        resetDrawerAutoHide()
    }

    private fun centerChannelSelection(position: Int) {
        if (position !in channels.indices || channelList.height <= 0) return
        val rowHeight = dp(if (tvMode) 58 else 48)
        val top = ((channelList.height - rowHeight) / 2).coerceAtLeast(0)
        channelList.setSelectionFromTop(listPositionForChannel(position), top)
    }

    private fun scheduleGuideLoad(channel: Channel) {
        pendingGuideChannel = channel
        guideLoadGeneration += 1
        handler.removeCallbacks(guideLoadRunnable)
        showGuideLoading(channel)
        handler.postDelayed(guideLoadRunnable, 220)
    }

    private fun showGuideLoading(channel: Channel) {
        if (!::guideTitle.isInitialized) return
        val date = currentGuideDate()
        guideTitle.text = "节目单 · 今天"
        guideSubtitle.text = "${channel.name} · ${formatGuideDate(date)}"
        guideStatus.text = "正在加载节目单…"
        guideStatus.visibility = View.VISIBLE
        guideList.visibility = View.GONE
        guideAdapter.setPrograms(emptyList())
    }

    private fun loadGuide(channel: Channel, generation: Int) {
        val date = currentGuideDate()
        EpgRepository.load(channel.pid, date) { result ->
            handler.post {
                if (
                    isFinishing ||
                    isDestroyed ||
                    generation != guideLoadGeneration ||
                    pendingGuideChannel?.pid != channel.pid
                ) {
                    return@post
                }
                result.fold(
                    onSuccess = { programs ->
                        guideAdapter.setPrograms(programs)
                        guideSubtitle.text =
                            "${channel.name} · ${formatGuideDate(date)}"
                        if (programs.isEmpty()) {
                            guideStatus.text = "今天暂无节目单"
                            guideStatus.visibility = View.VISIBLE
                            guideList.visibility = View.GONE
                        } else {
                            guideStatus.visibility = View.GONE
                            guideList.visibility = View.VISIBLE
                            guideList.setSelection(
                                (guideAdapter.focusPosition - 1).coerceAtLeast(0)
                            )
                        }
                    },
                    onFailure = {
                        guideStatus.text = "节目单加载失败\n点按重试"
                        guideStatus.visibility = View.VISIBLE
                        guideList.visibility = View.GONE
                    },
                )
            }
        }
    }

    private fun currentGuideDate(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())

    private fun formatGuideDate(date: String): String = runCatching {
        val parser = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val formatter = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        formatter.format(parser.parse(date) ?: Date())
    }.getOrDefault(date)

    private fun selectChannel(channel: Channel) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (channel.pid == lastChannelPickPid && now - lastChannelPickAt < 400L) return
        lastChannelPickPid = channel.pid
        lastChannelPickAt = now
        startAuth(channel)
        val position = channels.indexOfFirst { it.pid == channel.pid }
        scheduleGuideLoad(channel)
        channelList.post {
            if (drawer.visibility != View.VISIBLE) return@post
            if (position >= 0) {
                channelList.setSelection(listPositionForChannel(position))
                centerChannelSelection(position)
            }
            channelList.requestFocusFromTouch()
            channelList.requestFocus()
        }
        resetDrawerAutoHide()
    }

    private fun moveChannelSelection(direction: Int) {
        if (channels.isEmpty()) return
        val last = channels.lastIndex
        val selected = channelIndexFromListPosition(channelList.selectedItemPosition)
        val current = if (selected in 0..last) {
            selected
        } else {
            channels.indexOfFirst { it.pid == currentChannel?.pid }.coerceAtLeast(0)
        }
        val target = (current + direction).coerceIn(0, last)
        channelList.setSelection(listPositionForChannel(target))
        centerChannelSelection(target)
        scheduleGuideLoad(channels[target])
    }

    private fun hideChannelDrawer() {
        handler.removeCallbacks(drawerAutoHideRunnable)
        handler.removeCallbacks(guideLoadRunnable)
        guideLoadGeneration += 1
        pendingGuideChannel = null
        if (::drawer.isInitialized) {
            drawer.visibility = View.GONE
        }
        if (
            ::loadingOverlay.isInitialized &&
            loadingOverlay.visibility == View.VISIBLE
        ) {
            loadingOverlay.bringToFront()
        }
    }

    private fun dismissDrawerStep() {
        if (!::drawer.isInitialized || drawer.visibility != View.VISIBLE) return
        if (::guidePane.isInitialized && guidePane.visibility == View.VISIBLE) {
            setDrawerGuideVisible(false)
            guideList.clearFocus()
            channelList.requestFocus()
            resetDrawerAutoHide()
        } else {
            hideChannelDrawer()
        }
    }

    private fun setDrawerGuideVisible(visible: Boolean) {
        if (!::guidePane.isInitialized || !::drawerSeparator.isInitialized) return
        guidePane.visibility = if (visible) View.VISIBLE else View.GONE
        drawerSeparator.visibility = if (visible) View.VISIBLE else View.GONE
        val params = drawer.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = if (visible) drawerFullWidth else drawerChannelWidth
        if (params.width != width) {
            params.width = width
            drawer.layoutParams = params
        }
    }

    private fun resetDrawerAutoHide() {
        handler.removeCallbacks(drawerAutoHideRunnable)
        if (::drawer.isInitialized && drawer.visibility == View.VISIBLE) {
            handler.postDelayed(drawerAutoHideRunnable, DRAWER_AUTO_HIDE_MS)
        }
    }

    private fun isChannelToggleKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    companion object {
        private const val DRAWER_AUTO_HIDE_MS = 5_000L
        private const val TAP_SLOP_DP = 24
        private const val CCTV_FALLBACK_TIMEOUT_MS = 10_000L
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
            window.decorView.windowInsetsController?.let { controller ->
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class EpgAdapter(
        private val tvMode: Boolean,
    ) : BaseAdapter() {
        private var programs: List<EpgProgram> = emptyList()
        private var currentPosition = -1
        var focusPosition = 0
            private set

        fun setPrograms(value: List<EpgProgram>) {
            programs = value
            val now = System.currentTimeMillis() / 1000L
            currentPosition = programs.indexOfFirst {
                now >= it.startTime && now < it.endTime
            }
            val nextPosition = programs.indexOfFirst { it.endTime > now }
            focusPosition = when {
                currentPosition >= 0 -> currentPosition
                nextPosition >= 0 -> nextPosition
                programs.isNotEmpty() -> programs.lastIndex
                else -> 0
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = programs.size

        override fun getItem(position: Int): EpgProgram = programs[position]

        override fun getItemId(position: Int): Long =
            programs[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(parent?.context).apply {
                isFocusable = false
                isClickable = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(0xFFE8F2FF.toInt())
                setPadding(dp(8), 0, dp(8), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = programRowBackground()
            }
            val program = getItem(position)
            val start = program.startLabel.ifBlank { "--:--" }
            val end = program.endLabel.ifBlank { "--:--" }
            val isCurrent = position == currentPosition
            view.text = buildString {
                if (isCurrent) append("● ")
                append("$start–$end  ${program.title}")
            }
            view.setTextColor(
                if (isCurrent) 0xFF90CAF9.toInt() else 0xFFE8F2FF.toInt()
            )
            view.setTypeface(
                Typeface.DEFAULT,
                if (isCurrent) Typeface.BOLD else Typeface.NORMAL,
            )
            view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (tvMode) 18f else 14f,
            )
            view.isSelected = isCurrent
            view.layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (tvMode) 52 else 42),
            )
            return view
        }

        private fun programRowBackground(): StateListDrawable =
            StateListDrawable().apply {
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

    private class ChannelAdapter(
        private val channels: List<Channel>,
        private val tvMode: Boolean,
        private val onChannelClick: (Channel) -> Unit,
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
                isFocusableInTouchMode = true
                isClickable = true
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(20), 0, dp(20), 0)
                background = rowBackground()
            }
            val channel = getItem(position)
            view.text = channel.name
            view.setOnClickListener { onChannelClick(getItem(position)) }
            view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (tvMode) 20f else 16f,
            )
            val isCurrent = channel.pid == selectedPid
            view.isSelected = isCurrent
            view.background = if (isCurrent) {
                currentRowBackground()
            } else {
                rowBackground()
            }
            view.layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (tvMode) 58 else 48),
            )
            return view
        }

        private fun currentRowBackground(): StateListDrawable = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                ColorDrawable(0xFF1E88E5.toInt()),
            )
            addState(intArrayOf(), ColorDrawable(0xFF0D47A1.toInt()))
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

