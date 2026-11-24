package io.github.emiliounitydev.autoclicker.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import io.github.emiliounitydev.autoclicker.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Puente hacia el servicio de accesibilidad, que vive en su propio proceso
 * (`:gestures`, unos pocos MB).
 *
 * Motivo: con el juego abierto durante horas el sistema se queda sin memoria
 * y Android mata procesos. Antes la accesibilidad iba en el mismo proceso que
 * la captura de pantalla (~100 MB): al matarlo caía también la accesibilidad y
 * Android la dejaba «activada pero sin funcionar» hasta desactivarla y
 * activarla a mano. Ahora el proceso grande puede morir sin arrastrarla.
 */
class GestureBridge : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo ?: return
            val id = msg.arg1
            val d = msg.data
            val svc = ClickerAccessibilityService.instance
            if (svc == null) {
                send(reply, id, NOT_RUNNING)
                return
            }
            when (msg.what) {
                PING -> send(reply, id, OK)
                BACK -> scope.launch { svc.back(); send(reply, id, OK) }
                HOME -> scope.launch { svc.home(); send(reply, id, OK) }
                TAP -> scope.launch {
                    val ok = svc.tap(d.getInt("x"), d.getInt("y"), d.getInt("ms"))
                    send(reply, id, if (ok) OK else CANCELLED)
                }
                DRAG -> scope.launch {
                    val ok = svc.drag(d.getInt("x1"), d.getInt("y1"), d.getInt("x2"), d.getInt("y2"),
                        d.getInt("ms"), d.getInt("hold"))
                    send(reply, id, if (ok) OK else CANCELLED)
                }
            }
        }
    }
    private val messenger = Messenger(handler)

    private fun send(to: Messenger, id: Int, result: Int) {
        try {
            to.send(Message.obtain(null, REPLY, id, result))
        } catch (_: RemoteException) {
            // El proceso que pidió el gesto ha muerto: nada que hacer.
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val PING = 1
        const val TAP = 2
        const val DRAG = 3
        const val BACK = 4
        const val HOME = 5
        const val REPLY = 100
        const val OK = 1
        const val CANCELLED = 2
        const val NOT_RUNNING = 0
    }
}

/** Cliente (proceso principal) del [GestureBridge]. */
object GestureClient {
    @Volatile private var remote: Messenger? = null
    @Volatile private var binding = false
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (Int) -> Unit>()
    @Volatile var lastKnownRunning: Boolean? = null
        private set

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == GestureBridge.REPLY) pending.remove(msg.arg1)?.invoke(msg.arg2)
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // El proceso de gestos ha muerto; Android lo vuelve a crear solo.
            remote = null
            lastKnownRunning = false
            Diag.log("gestos", "proceso de accesibilidad desconectado")
            pending.values.forEach { it(GestureBridge.NOT_RUNNING) }
            pending.clear()
        }
    }

    fun bind(ctx: Context) {
        if (binding) return
        binding = ctx.applicationContext.bindService(
            Intent(ctx.applicationContext, GestureBridge::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun post(what: Int, data: Bundle?, cb: (Int) -> Unit): Boolean {
        val r = remote ?: return false
        val id = nextId.getAndIncrement()
        pending[id] = cb
        return try {
            r.send(Message.obtain(null, what, id, 0).apply { replyTo = replies; data?.let { setData(it) } })
            true
        } catch (_: RemoteException) {
            pending.remove(id)
            false
        }
    }

    /** Resultado de una orden (OK / CANCELLED / NOT_RUNNING) o null si no hubo respuesta. */
    suspend fun request(ctx: Context, what: Int, data: Bundle? = null, timeoutMs: Long = 3000): Int? {
        bind(ctx)
        return withTimeoutOrNull(timeoutMs) {
            // Esperar a que el enlace esté listo (primer uso o tras reiniciarse).
            while (remote == null) kotlinx.coroutines.delay(50)
            suspendCancellableCoroutine { cont ->
                val sent = post(what, data) { res -> if (cont.isActive) cont.resume(res) }
                if (!sent && cont.isActive) cont.resume(GestureBridge.NOT_RUNNING)
            }
        }.also { lastKnownRunning = it != null && it != GestureBridge.NOT_RUNNING }
    }

    suspend fun tap(ctx: Context, x: Int, y: Int, ms: Int) = request(ctx, GestureBridge.TAP,
        Bundle().apply { putInt("x", x); putInt("y", y); putInt("ms", ms) }, ms + 3000L)

    suspend fun drag(ctx: Context, x1: Int, y1: Int, x2: Int, y2: Int, ms: Int, hold: Int) =
        request(ctx, GestureBridge.DRAG, Bundle().apply {
            putInt("x1", x1); putInt("y1", y1); putInt("x2", x2); putInt("y2", y2); putInt("ms", ms); putInt("hold", hold)
        }, ms + hold + 3000L)

    suspend fun back(ctx: Context) = request(ctx, GestureBridge.BACK)
    suspend fun home(ctx: Context) = request(ctx, GestureBridge.HOME)

    /** Comprobación sin bloquear (para la interfaz): llama a `cb` en el hilo principal. */
    fun ping(ctx: Context, cb: (Boolean) -> Unit) {
        bind(ctx)
        val main = Handler(Looper.getMainLooper())
        fun attempt(left: Int) {
            if (remote == null) {
                if (left > 0) main.postDelayed({ attempt(left - 1) }, 100) else { lastKnownRunning = false; cb(false) }
                return
            }
            var done = false
            val ok = post(GestureBridge.PING, null) { res ->
                if (!done) { done = true; lastKnownRunning = res == GestureBridge.OK; cb(res == GestureBridge.OK) }
            }
            if (!ok) { done = true; lastKnownRunning = false; cb(false); return }
            main.postDelayed({ if (!done) { done = true; lastKnownRunning = false; cb(false) } }, 1500)
        }
        attempt(20)
    }
}
