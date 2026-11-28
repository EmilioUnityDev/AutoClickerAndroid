package io.github.emiliounitydev.autoclicker.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
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
import io.github.emiliounitydev.autoclicker.Diag
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
import kotlinx.coroutines.delay
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
    // Fotogramas: el juego redibuja a 60–120 fps; convertir cada uno generaba
    // cientos de MB/s de basura durante horas. Se convierte como mucho uno cada
    // FRAME_MIN_MS (el reproductor mira cada 80–300 ms) y se reutiliza el búfer.
    private var lastConvert = 0L
    private var byteBuf = ByteArray(0)
    @Volatile private var framesConverted = 0L
    @Volatile private var framesSeen = 0L
    private var startedAt = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            GestureClient.ping(this@RunnerService) { beat() }
        }

        fun beat() {
            if (GestureClient.lastKnownRunning == false) Diag.log("gestos", "latido: el proceso de accesibilidad NO responde")
            Diag.log("latido", "en marcha ${(System.currentTimeMillis() - startedAt) / 60000} min · " +
                "secuencia ${if (job?.isActive == true) "activa" else "parada"} · accesibilidad " +
                "${accText()} · captura " +
                "${if (projection != null) "OK" else "parada"} · fotogramas ${framesConverted}/${framesSeen} · ${Diag.memory()}")
            Diag.log("memoria", Diag.system(this@RunnerService))
            // Cada 2 min: para ver la curva de memoria del sistema antes del
            // cierre de madrugada (con 10 min el último dato quedaba lejos).
            main.postDelayed(this, 2 * 60_000L)
        }
    }

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
        startedAt = System.currentTimeMillis()
        GestureClient.bind(this)
        Diag.log("panel", "servicio creado · pantalla ${w}×${h}")
        Diag.log("memoria", Diag.system(this))
        main.postDelayed(heartbeat, 2 * 60_000L)
    }

    private fun accText() = when (GestureClient.lastKnownRunning) {
        true -> "OK"; false -> "CAÍDA"; null -> "?"
    }

    override fun onLowMemory() {
        Diag.log("panel", "aviso de POCA MEMORIA del sistema · ${Diag.memory()}")
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        Diag.log("panel", "onTrimMemory ${Diag.trimName(level)} · ${Diag.memory()} · ${Diag.system(this)}")
        super.onTrimMemory(level)
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
            ACTION_RECAPTURE -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = resultData(intent)
                if (projection == null && data != null && code == Activity.RESULT_OK) {
                    startCapture(code, data)
                    setStatus("Captura restablecida")
                }
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
        Diag.log("panel", "servicio destruido tras ${(System.currentTimeMillis() - startedAt) / 60000} min")
        main.removeCallbacks(heartbeat)
        job?.cancel()
        scope.cancel()
        panel?.let { runCatching { wm.removeView(it) } }
        stopCapture()
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
    /**
     * Para la captura sin carreras: el lector se cierra en su propio hilo, así
     * nunca se cierra una imagen mientras se está copiando (antes eso tiraba
     * la app entera, y con ella el servicio de accesibilidad, que Android deja
     * marcado como «No funciona» hasta desactivarlo y activarlo).
     */
    private fun stopCapture() {
        val r = reader
        val ht = thread
        reader = null
        thread = null
        lastFrame = null
        runCatching { r?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        if (ht != null && ht.isAlive) {
            Handler(ht.looper).post { runCatching { r?.close() } }
            ht.quitSafely()
        } else runCatching { r?.close() }
    }

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
                Diag.log("captura", "MediaProjection detenida por el sistema")
                main.post {
                    stopCapture()
                    // Con una secuencia en marcha (p. ej. tras reiniciar el juego):
                    // pedir el permiso otra vez; la accesibilidad lo acepta sola.
                    if (job?.isActive == true) {
                        setStatus("Captura detenida: pidiéndola de nuevo…")
                        Consent.request(this@RunnerService)
                    } else setStatus("Captura detenida por el sistema: vuelve a iniciar el panel desde la app")
                }
            }
        }, handler)
        // Media resolución: suficiente para condiciones de color y más ligero.
        val cw = screenWidth / 2
        val ch = screenHeight / 2
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            if (reader !== ir) return@setOnImageAvailableListener  // captura ya parada
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            framesSeen++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastConvert < FRAME_MIN_MS) {
                runCatching { img.close() }
                return@setOnImageAvailableListener
            }
            lastConvert = now
            val w = img.width
            val h = img.height
            // 1) Copiar los bytes de golpe (rápido) y soltar la imagen.
            val copy = try {
                val plane = img.planes[0]
                val buf = plane.buffer.duplicate().apply { rewind() }
                if (byteBuf.size != buf.remaining()) byteBuf = ByteArray(buf.remaining())
                val bytes = byteBuf.also { buf.get(it) }
                Triple(bytes, plane.pixelStride, plane.rowStride)
            } catch (e: Exception) {
                // Buffer inaccesible (captura parada a medias): se ignora este fotograma.
                null
            } finally {
                runCatching { img.close() }
            } ?: return@setOnImageAvailableListener
            // 2) Convertir a píxeles fuera de la imagen.
            val (bytes, pixelStride, rowStride) = copy
            val out = IntArray(w * h)
            for (y in 0 until h) {
                var off = y * rowStride
                val row = y * w
                if (off + (w - 1) * pixelStride + 2 >= bytes.size) break
                for (x in 0 until w) {
                    val rr = bytes[off].toInt() and 0xff
                    val gg = bytes[off + 1].toInt() and 0xff
                    val bb = bytes[off + 2].toInt() and 0xff
                    out[row + x] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
                    off += pixelStride
                }
            }
            lastFrame = Frame(w, h, out)
            framesConverted++
        }, handler)
        Diag.log("captura", "iniciada ${cw}×${ch}")
        display = mp.createVirtualDisplay("autoclicker", cw, ch, ScreenInfo.density(this) / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)
    }

    // ------------------------------------------------------------ DeviceIO
    // Los gestos los hace el servicio de accesibilidad en su propio proceso.
    // Si la accesibilidad ha caído y la app puede reactivarla (permiso dado
    // por adb), se repara y se repite el gesto una vez antes de detenerse.
    private suspend fun gesture(send: suspend () -> Int?) {
        val res = send()
        if (res != null && res != GestureBridge.NOT_RUNNING) return
        if (AccRepair.repairAndWait(this)) {
            val again = send()
            if (again != null && again != GestureBridge.NOT_RUNNING) return
        }
        throw StopSequence(ACC_STOPPED)
    }

    override suspend fun tap(x: Int, y: Int, durationMs: Int) {
        gesture { GestureClient.tap(this, x, y, durationMs) }
    }

    override suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int) {
        gesture { GestureClient.drag(this, x1, y1, x2, y2, durationMs, holdMs) }
    }

    override suspend fun back() {
        GestureClient.back(this)
    }

    /**
     * Cierra y vuelve a abrir el juego (libera la memoria que acumula durante
     * horas): inicio del móvil → el juego queda en segundo plano y se puede
     * cerrar con killBackgroundProcesses → se abre de nuevo. Abrir una
     * actividad desde un servicio está permitido porque la app tiene permiso
     * para mostrarse encima (el panel).
     */
    override suspend fun restartApp(pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Diag.log("juego", "no se encuentra cómo abrir $pkg")
            return false
        }
        Diag.log("juego", "reinicio de $pkg · ${Diag.system(this)}")
        GestureClient.home(this)
        delay(2500)
        runCatching { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) }
            .onFailure { Diag.log("juego", "no se pudo cerrar: $it") }
        delay(3000)
        Diag.log("juego", "cerrado · ${Diag.system(this)}")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val ok = runCatching { startActivity(launch) }
            .onFailure { Diag.log("juego", "no se pudo abrir: $it") }.isSuccess
        if (ok) Diag.log("juego", "abierto de nuevo")
        return ok
    }

    override fun frame(): Frame? = lastFrame

    override fun availableMemMb(): Long? = runCatching {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.availMem / 1_048_576
    }.getOrNull()

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
        Diag.log("secuencia", "inicio «$name» · ${Diag.memory()}")
        val t0 = System.currentTimeMillis()
        job = scope.launch {
            val msg = try {
                Player(seq, this@RunnerService, listener).run(steps)
                "Terminada"
            } catch (e: StopSequence) {
                "Detenida: ${e.message}"
            } catch (e: CancellationException) {
                "Detenida"
            } catch (e: Exception) {
                Diag.log("secuencia", "EXCEPCIÓN: ${android.util.Log.getStackTraceString(e)}")
                "Error: ${e.message}"
            }
            Diag.log("secuencia", "fin «$name» tras ${(System.currentTimeMillis() - t0) / 60000} min: $msg · " +
                "accesibilidad ${accText()} · ${Diag.memory()}")
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
        const val ACTION_RECAPTURE = "recapture"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_SEQUENCE = "sequence"
        const val EXTRA_STEP_PATH = "step_path"
        const val EXTRA_AUTOPLAY = "autoplay"
        private const val FRAME_MIN_MS = 120L
        const val ACC_STOPPED = "Accesibilidad detenida por el sistema: en Ajustes → Accesibilidad, " +
            "desactiva AutoClicker y vuelve a activarlo"
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
