package com.antigravity.ghosttouchguardian

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.view.WindowManager.LayoutParams.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

// ─── Boot receiver ────────────────────────────────────────────────────────
class Boot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = P(ctx); if (!p.en && !p.pen) return
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, Svc::class.java).apply { action = K.A_START })
    }
}

// ─── Screen-state receiver ────────────────────────────────────────────────
class Scr(val onOn: () -> Unit, val onOff: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = when (i.action) {
        Intent.ACTION_SCREEN_ON  -> onOn()
        Intent.ACTION_SCREEN_OFF -> onOff()
        else -> Unit
    }
}

// ─── Stats data: shared between Ovr and StatsOvr ─────────────────────────
data class Stat(
    val h: Int, val g: Int,         // hScore, gScore
    val blk: Boolean,               // was blocked
    val jit: Float,                 // spatial jitter px
    val acc: Float,                 // accel delta
    val dw: Int,                    // dwell ms
    val ttl: Long                   // lifetime blocked count
)

// ─── Stats overlay: draggable, top-center, transparent bg ────────────────
class StatsOvr(ctx: Context) : View(ctx) {

    @Volatile var stat: Stat? = null
    private var dragX = 0f; private var dragY = 0f  // drag offset from touch point
    private var rawX = 0f;  private var rawY = 0f   // top-left corner of view in screen

    // paints
    private val bg = Paint().apply {
        color = 0xCC000000.toInt(); style = Paint.Style.FILL
    }
    private val allow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt(); textSize = 36f; typeface = Typeface.MONOSPACE
    }
    private val block = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4B4B.toInt(); textSize = 36f; typeface = Typeface.MONOSPACE
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E9BAE.toInt(); textSize = 30f; typeface = Typeface.MONOSPACE
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val s = stat
        val lines = if (s == null) arrayOf("GTG Stats — no touch yet")
        else arrayOf(
            if (s.blk) "▣ BLOCKED  h=${s.h} g=${s.g}" else "▢ ALLOWED  h=${s.h} g=${s.g}",
            "jit=${if (s.jit < 0) "n/a" else "%.1f".format(s.jit)}px  acc=${"%.3f".format(s.acc)}",
            "dw=${s.dw}ms  total=${s.ttl}"
        )
        val lh = 42f; val pad = 16f
        val w = 460f; val h = pad*2 + lines.size * lh
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, 14f, 14f, bg)
        lines.forEachIndexed { i, txt ->
            val p = if (i == 0 && s != null) (if (s.blk) block else allow) else dim
            c.drawText(txt, pad, pad + (i + 1) * lh - 6f, p)
        }
    }

    // drag handling — this view is FLAG_NOT_FOCUSABLE so touch goes here first
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // raw coordinates on screen
        val loc = IntArray(2); getLocationOnScreen(loc)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX - loc[0]; dragY = e.rawY - loc[1]
            }
            MotionEvent.ACTION_MOVE -> {
                rawX = e.rawX - dragX; rawY = e.rawY - dragY
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(this, buildLp(rawX.toInt(), rawY.toInt()))
            }
        }
        return true  // consume: stats widget does not pass touches
    }

    companion object {
        fun buildLp(x: Int = 0, y: Int = 120): LayoutParams = LayoutParams(
            480, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") TYPE_PHONE,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.x = x; this.y = y
        }
    }
}

// ─── Main overlay: intercepts all touches ────────────────────────────────
class Ovr(ctx: Context, private val svc: Svc) : View(ctx) {

    private val bld = CtxBuilder()

    override fun onHoverEvent(e: MotionEvent): Boolean {
        // S-Pen hover above glass → record stylus presence time
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) St.lSty = e.eventTime
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)

        // ── Stylus: always allow, track lift time for echo window ─────────
        if (tool == MotionEvent.TOOL_TYPE_STYLUS) {
            if (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL) St.lSty = e.eventTime
            return false
        }

        // ── S-Pen exclusive: block every capacitive event ─────────────────
        if (svc.p.pen) {
            svc.postBlock(null)
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                bld.down(e)

                // Burst gate at DOWN (zero latency)
                if (St.lt > 0L && (e.eventTime - St.lt) in 1L..(K.BI - 1L)) {
                    val dx = e.x - St.lx; val dy = e.y - St.ly
                    if (sqrt(dx * dx + dy * dy) < K.BD) {
                        St.lt = e.eventTime; St.lx = e.x; St.ly = e.y
                        svc.postBlock(Stat(0, 6, true, -1f,
                            svc.sen.peak(e.eventTime), 0, svc.p.blk + 1))
                        return true
                    }
                }

                // Echo gate at DOWN (zero latency)
                val tsSty = if (St.lSty > 0L) e.eventTime - St.lSty else -1L
                if (tsSty in K.E0..K.E1 && svc.sen.peak(e.eventTime) < K.AT) {
                    St.lt = e.eventTime; St.lx = e.x; St.ly = e.y
                    svc.postBlock(Stat(0, 5, true, -1f,
                        svc.sen.peak(e.eventTime), 0, svc.p.blk + 1))
                    return true
                }

                St.lt = e.eventTime; St.lx = e.x; St.ly = e.y
                return false
            }

            MotionEvent.ACTION_MOVE -> { bld.move(e); return false }

            MotionEvent.ACTION_UP -> {
                val c   = bld.up(e, svc.sen)
                val zns = ZStore.fromJson(svc.p.zns)
                val res = isGScored(c, zns)   // returns Pair(isGhost, Stat)
                val ghost = res.first

                if (!ghost) {
                    St.lUp = e.eventTime
                    St.pushOri(c.ori)
                    svc.postAllow(res.second)
                    return false
                }

                // Inject ACTION_CANCEL so apps undo the gesture they already received
                try {
                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    this.rootView?.dispatchTouchEvent(cancel)
                    cancel.recycle()
                } catch (_: Exception) {}

                svc.postBlock(res.second)
                return true
            }

            MotionEvent.ACTION_CANCEL -> return false
        }
        return false
    }
}

// ─── Foreground service ───────────────────────────────────────────────────
class Svc : Service() {

    lateinit var p:   P
    lateinit var sen: Sen
    lateinit var wm:  WindowManager
    private var ovr:  Ovr?      = null
    private var sov:  StatsOvr? = null
    private var scr:  Scr?      = null

    companion object { @Volatile var inst: Svc? = null }

    override fun onCreate() {
        super.onCreate(); inst = this
        p   = P(this)
        sen = Sen(this).also { it.start() }
        wm  = getSystemService(WINDOW_SERVICE) as WindowManager
        mkChannel(); startFg()
        if (p.en || p.pen) attachOverlay()
        regScr()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        mkChannel(); startFg()
        when (i?.action) {
            K.A_TOGGLE -> {
                p.en = !p.en
                if (p.en) attachOverlay() else detachOverlay()
                updNotif()
            }
            K.A_PEN -> {
                p.pen = !p.pen
                if (p.pen) attachOverlay()
                updNotif()
            }
            K.A_STATS -> {
                p.stats = !p.stats
                if (p.stats) attachStats() else detachStats()
                updNotif()
            }
            K.A_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    // Called by Ovr when a ghost is confirmed
    fun postBlock(s: Stat?) {
        p.blk = p.blk + 1
        val stat = s?.copy(ttl = p.blk) ?: Stat(0, 0, true, -1f, 0f, 0, p.blk)
        Log.d("GTG", "BLOCK h=${stat.h} g=${stat.g} jit=${stat.jit} acc=${stat.acc} dw=${stat.dw}")
        sov?.let { it.stat = stat; it.postInvalidate() }
        updNotif()
    }

    // Called by Ovr when touch confirmed human
    fun postAllow(s: Stat) {
        Log.d("GTG", "ALLOW h=${s.h} g=${s.g} jit=${s.jit} acc=${s.acc} dw=${s.dw}")
        sov?.let { it.stat = s; it.postInvalidate() }
    }

    private fun attachOverlay() {
        if (ovr != null) return
        if (!canDrawOverlays()) return
        val lp = LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") TYPE_PHONE,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        ovr = Ovr(this, this).also { wm.addView(it, lp) }
    }

    private fun detachOverlay() {
        ovr?.let { try { wm.removeView(it) } catch (_: Exception) {} }; ovr = null
    }

    private fun attachStats() {
        if (sov != null) return
        if (!canDrawOverlays()) return
        sov = StatsOvr(this).also { wm.addView(it, StatsOvr.buildLp()) }
    }

    private fun detachStats() {
        sov?.let { try { wm.removeView(it) } catch (_: Exception) {} }; sov = null
    }

    private fun canDrawOverlays() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.provider.Settings.canDrawOverlays(this) else true

    private fun regScr() {
        scr = Scr(
            onOn  = { sen.start(); if (p.en || p.pen) attachOverlay() },
            onOff = { sen.stop() }
        )
        ContextCompat.registerReceiver(this, scr,
            IntentFilter().also {
                it.addAction(Intent.ACTION_SCREEN_ON)
                it.addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onTaskRemoved(r: Intent?) {
        if (p.en || p.pen)
            ContextCompat.startForegroundService(this,
                Intent(this, Svc::class.java).apply { action = K.A_START })
    }

    override fun onDestroy() {
        super.onDestroy()
        detachOverlay(); detachStats()
        sen.stop()
        scr?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        inst = null
    }

    override fun onBind(i: Intent?): IBinder? = null

    // ── Notification ──────────────────────────────────────────────────────
    private fun mkChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(K.CH_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(K.CH_ID, "Ghost Touch Guardian",
                NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
    }

    private fun startFg() {
        val n = buildNotif()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                startForeground(K.N_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(K.N_ID, n)
        } catch (_: Exception) {}
    }

    fun updNotif() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(K.N_ID, buildNotif())
    }

    private fun buildNotif(): Notification {
        fun svcPi(reqCode: Int, action: String) = PendingIntent.getService(
            this, reqCode, Intent(this, Svc::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val txt = when {
            p.pen   -> "🖊 S-Pen exclusive · ${p.blk} blocked"
            p.en    -> "✅ Active · ${p.blk} blocked"
            else    -> "⏸ Paused"
        }
        return NotificationCompat.Builder(this, K.CH_ID)
            .setContentTitle("Ghost Touch Guardian")
            .setContentText(txt)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, UI::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.ic_shield, if (p.en) "Pause" else "Resume", svcPi(1, K.A_TOGGLE))
            .addAction(R.drawable.ic_shield, if (p.pen) "🖊 ON" else "🖊 OFF",  svcPi(2, K.A_PEN))
            .addAction(R.drawable.ic_shield, if (p.stats) "Stats OFF" else "Stats ON", svcPi(3, K.A_STATS))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}
