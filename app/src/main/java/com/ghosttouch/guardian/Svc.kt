package com.ghosttouch.guardian

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
import com.ghosttouch.guardian.L
import com.ghosttouch.guardian.Tags
import kotlin.math.sqrt

// Boot receiver
class Boot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = P(ctx); if (!p.en && !p.pen) return
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, Svc::class.java).apply { action = K.A_START })
    }
}

// Screen on/off receiver
class Scr(val onOn: ()->Unit, val onOff: ()->Unit) : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = when(i.action) {
        Intent.ACTION_SCREEN_ON  -> onOn()
        Intent.ACTION_SCREEN_OFF -> onOff()
        else -> Unit
    }
}

// Stats overlay: draggable floating widget
class StatsOvr(ctx: Context) : View(ctx) {

    @Volatile var vrd: Vrd? = null
    @Volatile var ttl: Long = 0L
    private var dox = 0f; private var doy = 0f

    private val bgP  = Paint().apply { color = 0xDD0A0F1A.toInt() }
    private val okP  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xFF00E676.toInt(); textSize=38f; typeface=Typeface.MONOSPACE }
    private val blkP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xFFFF4B4B.toInt(); textSize=38f; typeface=Typeface.MONOSPACE }
    private val dimP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xFF8E9BAE.toInt(); textSize=30f; typeface=Typeface.MONOSPACE }
    private val rf   = RectF()

    override fun onDraw(c: Canvas) {
        val v = vrd
        val line1: String; val line2: String; val line3: String
        if (v == null) {
            line1 = "GTG  waiting…"; line2 = ""; line3 = ""
        } else {
            line1 = if (v.ghost) "▣ BLOCK  h=${v.h} g=${v.g}" else "▢ ALLOW  h=${v.h} g=${v.g}"
            line2 = "jit=${if(v.j<0)"n/a" else "%.1f".format(v.j)}px  ${v.why}"
            line3 = "total blocked: $ttl"
        }
        val lh=44f; val pd=18f; val rows=if(v==null) 1 else 3
        val w=520f; val h=pd*2+rows*lh
        rf.set(0f,0f,w,h)
        c.drawRoundRect(rf, 16f, 16f, bgP)
        val p1 = if (v!=null && v.ghost) blkP else okP
        c.drawText(line1, pd, pd+lh-6f, p1)
        if (v != null) {
            c.drawText(line2, pd, pd+lh*2-6f, dimP)
            c.drawText(line3, pd, pd+lh*3-6f, dimP)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val loc = IntArray(2); getLocationOnScreen(loc)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dox=e.rawX-loc[0]; doy=e.rawY-loc[1] }
            MotionEvent.ACTION_MOVE -> {
                val nx=(e.rawX-dox).toInt(); val ny=(e.rawY-doy).toInt()
                try {
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(this, mkLp(nx, ny))
                } catch(e: Exception) {
                    L.w(Tags.OVERLAY, "Failed to update stats overlay layout", e)
                }
            }
        }
        return true
    }

    companion object {
        fun mkLp(x:Int=0, y:Int=100): WindowManager.LayoutParams =
            WindowManager.LayoutParams(
                540, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") TYPE_PHONE,
                FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).also { it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; it.x=x; it.y=y }
    }
}

// Interception overlay: pass-through/block mechanism
class Ovr(ctx: Context, private val sv: Svc) : View(ctx) {

    private val bld = CtxBuilder()
    private val hnd = Handler(Looper.getMainLooper())
    private var passThrough = false

    private val reArm = Runnable {
        if (passThrough) {
            passThrough = false
            sv.updateOvrFlags(false)
        }
    }

    private fun openPassThrough() {
        passThrough = true
        sv.updateOvrFlags(true)
        hnd.removeCallbacks(reArm)
        hnd.postDelayed(reArm, K.PT)
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            St.lSty = e.eventTime
            StylusMonitor.recordStylusTouch(e.eventTime)  // v4.2.0
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)

        if (tool == MotionEvent.TOOL_TYPE_STYLUS) {
            if (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL) {
                St.lSty = e.eventTime
                StylusMonitor.recordStylusTouch(e.eventTime)  // v4.2.0
            }
            return false
        }

        if (sv.p.pen) {
            sv.blocked(Vrd(true,0,9,-1f,"PenMode"), null)
            return true
        }

        when (e.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val zns = sv.cachedZones
                val v = qDecide(e, sv.sen, zns, sv.p.pen)

                if (v.ghost) {
                    bld.down(e)
                    St.lt=e.eventTime; St.lx=e.x; St.ly=e.y
                    sv.blocked(v, null)
                    return true
                }

                St.lt=e.eventTime; St.lx=e.x; St.ly=e.y
                St.lUp=e.eventTime
                openPassThrough()
                sv.allowed(v)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                bld.move(e)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val c = bld.up(e, sv.sen)
                val zns = sv.cachedZones
                val v = decide(c, zns)

                if (!v.ghost) {
                    L.w(Tags.DETECTION, "FALSE BLOCK? ${v.why} h=${v.h} g=${v.g} j=${v.j}")
                }

                St.lUp = e.eventTime

                sv.blocked(v, c)
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}

// Foreground service
class Svc : Service() {

    lateinit var p:  P
    lateinit var sen:Sen
    lateinit var wm: WindowManager
    private var ovr: Ovr?      = null
    private var ovrLp: WindowManager.LayoutParams? = null
    private var sov: StatsOvr? = null
    private var scr: Scr?      = null
    @Volatile internal var cachedZones: List<Z> = emptyList()

    companion object { @Volatile var inst: Svc? = null }

    override fun onCreate() {
        super.onCreate(); inst = this
        L.i(Tags.SERVICE, "Service creating")
        p   = P(this)
        sen = Sen(this).also { it.start() }
        wm  = getSystemService(WINDOW_SERVICE) as WindowManager
        Rec.load(p)
        Stats.init(this)      // load adaptive thresholds
        Rec.open(this)   // open CSV log file
        reloadZones()   // cache safe zones
        mkCh(); startFg()
        if (p.en || p.pen) attachOvr()
        if (p.stats)        attachSov()
        regScr()
        L.i(Tags.SERVICE, "Service created successfully")
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        L.i(Tags.SERVICE, "onStartCommand action=${i?.action}")
        mkCh(); startFg()
        when (i?.action) {
            K.A_TOGGLE -> { p.en = !p.en;  if (p.en) attachOvr() else detachOvr(); updN() }
            K.A_PEN    -> { p.pen= !p.pen; if (p.pen) attachOvr(); updN() }
            K.A_STATS  -> { p.stats= !p.stats; if(p.stats) attachSov() else detachSov(); updN() }
            K.A_WRONG  -> {
                val msg = Rec.err(p)
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                updN()
            }
            K.A_STOP   -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    fun reloadZones() {
        cachedZones = ZStore.fromJson(p.zns)
        L.i(Tags.SERVICE, "Reloaded ${cachedZones.size} safe zones")
    }

    fun blocked(v: Vrd, c: Ctx?) {
        p.blk = p.blk + 1
        Stats.recordBlock(v.why)
        L.d(Tags.DETECTION, "BLOCK h=${v.h} g=${v.g} j=${v.j} ${v.why}")
        Rec.rec(v, c)       // record to CSV + store as last for error feedback
        sov?.let { it.vrd=v; it.ttl=p.blk; it.postInvalidate() }
        updN()
    }

    fun allowed(v: Vrd) {
        Stats.recordAllow(v.why)
        L.d(Tags.DETECTION, "ALLOW h=${v.h} g=${v.g} ${v.why}")
        Rec.rec(v, null)    // record to CSV (no full Ctx for passed touches)
        sov?.let { it.vrd=v; it.ttl=p.blk; it.postInvalidate() }
    }

    // Overlay management
    private fun mkLp(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") TYPE_PHONE,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

    private fun attachOvr() {
        if (ovr != null || !canOvr()) return
        L.i(Tags.OVERLAY, "Attaching main overlay")
        ovrLp = mkLp()
        ovr = Ovr(this, this).also { wm.addView(it, ovrLp!!) }
    }
    private fun detachOvr() {
        L.i(Tags.OVERLAY, "Detaching main overlay")
        ovr?.let{try{wm.removeView(it)}catch(e: Exception){
            L.w(Tags.OVERLAY, "Failed to detach overlay", e)
        }}; ovr=null; ovrLp=null
    }

    fun updateOvrFlags(passThrough: Boolean) {
        val lp = ovrLp ?: return
        val o = ovr ?: return
        if (passThrough) lp.flags = lp.flags or FLAG_NOT_TOUCHABLE
        else lp.flags = lp.flags and FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(o, lp) } catch(e: Exception) {
            L.w(Tags.OVERLAY, "Failed to update overlay flags", e)
        }
    }

    private fun attachSov() {
        if (sov != null || !canOvr()) return
        L.i(Tags.OVERLAY, "Attaching stats overlay")
        sov = StatsOvr(this).also { wm.addView(it, StatsOvr.mkLp()) }
    }
    private fun detachSov() {
        L.i(Tags.OVERLAY, "Detaching stats overlay")
        sov?.let{try{wm.removeView(it)}catch(e: Exception){
            L.w(Tags.OVERLAY, "Failed to detach stats overlay", e)
        }}; sov=null
    }

    private fun canOvr() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        android.provider.Settings.canDrawOverlays(this) else true

    private fun regScr() {
        scr = Scr(
            onOn  = { sen.start(); if (p.en||p.pen) attachOvr() },
            onOff = { sen.stop()  }
        )
        ContextCompat.registerReceiver(this, scr,
            IntentFilter().also {
                it.addAction(Intent.ACTION_SCREEN_ON)
                it.addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onTaskRemoved(r: Intent?) {
        if (p.en || p.pen) ContextCompat.startForegroundService(this,
            Intent(this, Svc::class.java).apply { action = K.A_START })
    }

    override fun onDestroy() {
        L.i(Tags.SERVICE, "Service destroying")
        super.onDestroy()
        detachOvr(); detachSov(); sen.stop()
        Rec.close()
        scr?.let { try { unregisterReceiver(it) } catch(e: Exception){
            L.w(Tags.SERVICE, "Failed to unregister screen receiver", e)
        } }
        inst = null
        L.i(Tags.SERVICE, "Service destroyed")
    }

    override fun onBind(i: Intent?): IBinder? = null

    // Notification
    private fun mkCh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(K.CH_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(
            K.CH_ID, "Ghost Touch Guardian", NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false); lockscreenVisibility=Notification.VISIBILITY_SECRET })
    }

    private fun startFg() = try {
        val n = buildN()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(K.N_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(K.N_ID, n)
    } catch(e: Exception) {
        L.e(Tags.SERVICE, "Failed to start foreground service", e)
    }

    fun updN() = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        .notify(K.N_ID, buildN())

    private fun spi(code:Int, act:String) = PendingIntent.getService(this, code,
        Intent(this, Svc::class.java).apply { action=act },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildN(): Notification {
        val txt = when { p.pen->"🖊 S-Pen exclusive · ${p.blk} blocked"
                         p.en ->"✅ Active · ${p.blk} blocked"
                         else ->"⏸ Paused" }
        return NotificationCompat.Builder(this, K.CH_ID)
            .setContentTitle("Ghost Touch Guardian").setContentText(txt)
            .setSmallIcon(R.drawable.ic_shield).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, UI::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.ic_shield, if(p.en) "Pause" else "Resume", spi(1,K.A_TOGGLE))
            .addAction(R.drawable.ic_shield, if(p.pen) "🖊 ON"  else "🖊 OFF", spi(2,K.A_PEN))
            .addAction(R.drawable.ic_shield, if(p.stats)"Stats▲"else "Stats▽", spi(3,K.A_STATS))
            .addAction(R.drawable.ic_shield, "Wrong?", spi(4,K.A_WRONG))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}

