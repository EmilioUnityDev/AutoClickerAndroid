package io.github.emiliounitydev.autoclicker.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.emiliounitydev.autoclicker.R
import io.github.emiliounitydev.autoclicker.engine.DeviceIO
import io.github.emiliounitydev.autoclicker.engine.Frame
import io.github.emiliounitydev.autoclicker.engine.Player
import io.github.emiliounitydev.autoclicker.engine.PlayerListener
import io.github.emiliounitydev.autoclicker.engine.StopSequence
import io.github.emiliounitydev.autoclicker.model.Sequence
import io.github.emiliounitydev.autoclicker.model.Step
import io.github.emiliounitydev.autoclicker.ui.MainActivity
import io.github.emiliounitydev.autoclicker.ui.SequenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene la captura de pantalla
 * (MediaProjection), el panel flotante ▶ ■ y el reproductor.
 */
class RunnerService : Service(), DeviceIO {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile private var lastFrame: Frame? = null

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var statusView: TextView? = null
    private var playButton: Button? = null

    override var screenWidth = 0
    override var screenHeight = 0

    private var sequenceName: String? = null
    private var singleStepPath: IntArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (w, h) = ScreenInfo.size(this)
        screenWidth = w; screenHeight = h
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground()
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = resultData(intent)
                if (projection == null && data != null && code == Activity.RESULT_OK) startCapture(code, data)
                sequenceName = intent.getStringExtra(EXTRA_SEQUENCE) ?: sequenceName
                showPanel()
                setStatus(sequenceName?.let { "Listo: $it" } ?: "Elige una secuencia en la app")
            }
            ACTION_SELECT -> {
                sequenceName = intent.getStringExtra(EXTRA_SEQUENCE)
                singleStepPath = intent.getIntArrayExtra(EXTRA_STEP_PATH)
                setStatus("Listo: $sequenceName" + if (singleStepPath != null) " (solo un paso)" else "")
                if (intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) startPlayer()
            }
            ACTION_STOP_ALL -> stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun resultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        panel?.let { runCatching { wm.removeView(it) } }
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------- notificación
    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reproductor", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RunnerService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("AutoClicker activo")
            .setContentText("Panel flotante y captura de pantalla en marcha")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cerrar", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ------------------------------------------------------------- captura
    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data) ?: return
        projection = mp
        val ht = HandlerThread("capture").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                lastFrame = null
                main.post { setStatus("Captura detenida por el sistema") }
            }
        }, handler)
        // Media resolución: suficiente para condiciones de color y más ligero.
        val cw = screenWidth / 2
        val ch = screenHeight / 2
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val w = img.width
                val h = img.height
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    var off = y * rowStride
                    val row = y * w
                    for (x in 0 until w) {
                        val rr = buf.get(off).toInt() and 0xff
                        val gg = buf.get(off + 1).toInt() and 0xff
                        val bb = buf.get(off + 2).toInt() and 0xff
                        out[row + x] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
                        off += pixelStride
                    }
                }
                lastFrame = Frame(w, h, out)
            } finally {
                img.close()
            }
        }, handler)
        display = mp.createVirtualDisplay("autoclicker", cw, ch, ScreenInfo.density(this) / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
    }

    // ------------------------------------------------------------ DeviceIO
    override suspend fun tap(x: Int, y: Int, durationMs: Int) {
        val svc = ClickerAccessibilityService.instance ?: throw StopSequence("Servicio de accesibilidad desactivado")
        svc.tap(x, y, durationMs)
    }

    override suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int) {
        val svc = ClickerAccessibilityService.instance ?: throw StopSequence("Servicio de accesibilidad desactivado")
        svc.drag(x1, y1, x2, y2, durationMs, holdMs)
    }

    override suspend fun back() {
        ClickerAccessibilityService.instance?.back()
    }

    override fun frame(): Frame? = lastFrame

    /** Fotograma actual (para el selector de color del editor). */
    fun currentFrame(): Frame? = lastFrame

    // ------------------------------------------------------------ reproductor
    private fun startPlayer() {
        if (job?.isActive == true) return
        val name = sequenceName ?: run { setStatus("Elige una secuencia en la app"); return }
        val seq = runCatching { SequenceStore(this).load(name) }.getOrElse {
            setStatus("No se pudo cargar «$name»: ${it.message}"); return
        }
        val missing = seq.missingPlugins()
        if (missing.isNotEmpty()) setStatus("Aviso: faltan complementos ${missing.joinToString()} (sus pasos se saltan)")
        val steps: List<Step> = singleStepPath?.let { listOf(SequenceStore.stepAt(seq, it)) } ?: seq.steps
        val listener = object : PlayerListener {
            override fun status(text: String) { main.post { setStatus(text) } }
        }
        main.post { playButton?.text = "■" }
        job = scope.launch {
            val msg = try {
                Player(seq, this@RunnerService, listener).run(steps)
                "Terminada"
            } catch (e: StopSequence) {
                "Detenida: ${e.message}"
            } catch (e: CancellationException) {
                "Detenida"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            main.post {
                playButton?.text = "▶"
                setStatus(msg)
            }
        }
    }

    private fun stopPlayer() {
        job?.cancel()
    }

    // --------------------------------------------------------------- panel
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { setColor(0xCC202030.toInt()); cornerRadius = dp(14).toFloat() }
        }
        val handle = TextView(this).apply { text = "⠿"; setTextColor(Color.WHITE); textSize = 20f; setPadding(dp(2), 0, dp(6), 0) }
        val play = Button(this).apply {
            text = "▶"; textSize = 18f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { if (job?.isActive == true) stopPlayer() else startPlayer() }
        }
        val close = Button(this).apply {
            text = "✕"; textSize = 14f; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { stopSelf() }
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; maxLines = 2; maxWidth = dp(190)
            setPadding(dp(6), 0, dp(4), 0)
        }
        box.addView(handle); box.addView(play); box.addView(status); box.addView(close)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = screenHeight / 3 }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }
        wm.addView(box, lp)
        panel = box; statusView = status; playButton = play
    }

    private fun setStatus(t: String) {
        statusView?.text = t
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_SELECT = "select"
        const val ACTION_STOP_ALL = "stop_all"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_SEQUENCE = "sequence"
        const val EXTRA_STEP_PATH = "step_path"
        const val EXTRA_AUTOPLAY = "autoplay"
        private const val CHANNEL = "runner"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: RunnerService? = null
            private set

        fun select(ctx: Context, name: String, stepPath: IntArray? = null, autoplay: Boolean = false) {
            ctx.startService(Intent(ctx, RunnerService::class.java).setAction(ACTION_SELECT)
                .putExtra(EXTRA_SEQUENCE, name).putExtra(EXTRA_STEP_PATH, stepPath).putExtra(EXTRA_AUTOPLAY, autoplay))
        }
    }
}
