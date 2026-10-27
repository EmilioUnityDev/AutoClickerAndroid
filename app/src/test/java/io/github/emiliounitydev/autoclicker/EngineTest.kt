package io.github.emiliounitydev.autoclicker

import io.github.emiliounitydev.autoclicker.engine.DeviceIO
import io.github.emiliounitydev.autoclicker.engine.Frame
import io.github.emiliounitydev.autoclicker.engine.Player
import io.github.emiliounitydev.autoclicker.engine.PlayerListener
import io.github.emiliounitydev.autoclicker.engine.StopSequence
import io.github.emiliounitydev.autoclicker.engine.Vision
import io.github.emiliounitydev.autoclicker.model.ClickStep
import io.github.emiliounitydev.autoclicker.model.ColorCondition
import io.github.emiliounitydev.autoclicker.model.Guard
import io.github.emiliounitydev.autoclicker.model.Mapping
import io.github.emiliounitydev.autoclicker.model.PluginStep
import io.github.emiliounitydev.autoclicker.model.Screen
import io.github.emiliounitydev.autoclicker.model.Sequence
import io.github.emiliounitydev.autoclicker.model.SubsequenceStep
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

private fun res(name: String) = Thread.currentThread().contextClassLoader!!.getResourceAsStream(name)!!

/** Captura de prueba: (ancho, alto) + RGB, gzip (ver tools/mask_test_images.py). */
private fun shot(name: String): Frame {
    DataInputStream(GZIPInputStream(res("raid/$name.rgb.gz"))).use { inp ->
        val w = inp.readInt(); val h = inp.readInt()
        val rgb = ByteArray(w * h * 3).also { inp.readFully(it) }
        val px = IntArray(w * h) { i ->
            val r = rgb[3 * i].toInt() and 0xff; val g = rgb[3 * i + 1].toInt() and 0xff; val b = rgb[3 * i + 2].toInt() and 0xff
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, px)
    }
}

class ModelTest {
    private val raidText = res("raid/raid_join.json").bufferedReader().readText()

    @Test
    fun pcSequenceRoundTrip() {
        val seq = Sequence.parse(raidText)
        assertEquals("pc", seq.origin?.platform)
        assertEquals(900, Mapping.sourceScreen(seq).width)
        assertTrue(seq.missingPlugins().isEmpty())
        val again = Sequence.parse(seq.toJson().toString())
        assertEquals(seq.toJson().getJSONArray("steps").toString(), again.toJson().getJSONArray("steps").toString())
        // Los campos de PC que no usamos se conservan.
        assertEquals(".Main", again.toJson().getJSONObject("target").getString("title"))
    }

    @Test
    fun unknownPluginIsKeptAndReported() {
        val json = """{"version":2,"name":"pesca","target":{"exe":"x","title":"y"},"steps":[
            {"type":"click","x":1,"y":2},
            {"type":"fishing_pull","name":"Minijuego","bar_region":[1,2,3,4],"delay_before_ms":5}]}"""
        val seq = Sequence.parse(json)
        assertTrue(seq.steps[1] is PluginStep)
        assertEquals(setOf("fishing"), seq.missingPlugins())
        val out = seq.toJson()
        assertEquals(JSONObject().put("x", 1).length(), 1)
        assertEquals("fishing_pull", out.getJSONArray("steps").getJSONObject(1).getString("type"))
        assertEquals(4, out.getJSONArray("steps").getJSONObject(1).getJSONArray("bar_region").getInt(3))
        assertEquals("fishing", out.getJSONArray("requires").getString(0))
    }
}

class MappingTest {
    @Test
    fun stretchSameFormat() {
        val m = Mapping.Mapper(Screen(900, 1600), Screen(1800, 3200), Mapping.Mode.STRETCH)
        assertEquals(900, m.x(450)); assertEquals(3000, m.y(1500))
    }

    @Test
    fun anchorDifferentFormat() {
        val src = Screen(900, 1600); val dst = Screen(1220, 2712)
        assertEquals(Mapping.Mode.ANCHOR, Mapping.defaultMode(src, dst))
        val m = Mapping.Mapper(src, dst, Mapping.Mode.ANCHOR)
        val k = 1220.0 / 900
        assertEquals(Math.round(100 * k).toInt(), m.y(100))                 // arriba: anclado arriba
        assertEquals(Math.round(2712 - 100 * k).toInt(), m.y(1500))         // abajo: anclado abajo
        for (y in listOf(50, 700, 900, 1550)) assertEquals(y.toDouble(), m.invY(m.y(y)).toDouble(), 1.0)
    }

    @Test
    fun remapRewritesAndUpdatesOrigin() {
        val seq = Sequence("t", null, mutableListOf(ClickStep(450, 1500)))
        val rep = Mapping.remap(seq, Screen(1220, 2712), Mapping.Mode.ANCHOR, "android", "test")
        val c = seq.steps[0] as ClickStep
        assertEquals(610, c.x)
        assertEquals("android", seq.origin?.platform)
        assertEquals(1, rep.changedSteps)
        assertTrue(rep.warnings.isNotEmpty())
    }
}

class VisionTest {
    @Test
    fun fractionMatchesPcMetric() {
        // 30×30 rojo puro en un fondo negro de 90×90; muestreo cada 3 px.
        val px = IntArray(90 * 90) { i -> val x = i % 90; val y = i / 90; if (x < 30 && y < 30) 0xffff0000.toInt() else 0xff000000.toInt() }
        val f = Frame(90, 90, px)
        val c = ColorCondition(0, 0, 60, 60, "#ff0000", 10, 0.2)
        assertEquals(0.25, Vision.fraction(f, c, 1.0, 1.0), 1e-9)
    }
}

/** Dispositivo simulado: cada pantalla es una captura y los toques cambian de pantalla. */
private class FakeDevice(var screen: String, val transitions: Map<String, List<Pair<IntArray, String>>>) : DeviceIO {
    val frames = HashMap<String, Frame>()
    val taps = mutableListOf<Pair<Int, Int>>()
    override val screenWidth = 900
    override val screenHeight = 1600
    override suspend fun tap(x: Int, y: Int, durationMs: Int) {
        taps += x to y
        transitions[screen]?.firstOrNull { (r, _) -> x in r[0]..r[2] && y in r[1]..r[3] }?.let { screen = it.second }
    }
    override suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int) { taps += x1 to y1 }
    override suspend fun back() {}
    override fun frame(): Frame = frames.getOrPut(screen) { shot(screen) }
}

class RaidJoinPlayerTest {
    private val t = mapOf(
        "summary" to listOf(intArrayOf(300, 1300, 600, 1370) to "chat_join"),
        "chat_join" to listOf(intArrayOf(335, 1100, 755, 1200) to "overview_join"),
        "overview_join" to listOf(intArrayOf(200, 1470, 840, 1540) to "overview_pending",
            intArrayOf(20, 1455, 125, 1555) to "map"),
        "overview_pending" to listOf(intArrayOf(20, 1455, 125, 1555) to "map"),
        "map" to listOf(intArrayOf(800, 1195, 885, 1275) to "chat_pending"),
    )

    private fun oneCycle(): Sequence {
        val seq = Sequence.parse(res("raid/raid_join.json").bufferedReader().readText())
        (seq.steps[0] as SubsequenceStep).repeats = 1
        return seq
    }

    @Test
    fun fullCycleFromChat() = runTest {
        val dev = FakeDevice("chat_join", t)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals("chat_pending", dev.screen)
        // Join del chat → Join del overview → flecha atrás → botón de chat
        assertEquals(4, dev.taps.size)
        assertTrue(dev.taps[0].second in 1100..1200)
        assertEquals(515 to 1505, dev.taps[1])
        assertEquals(70 to 1500, dev.taps[2])
        assertEquals(840 to 1235, dev.taps[3])
    }

    @Test
    fun summaryIsConfirmedFirst() = runTest {
        val dev = FakeDevice("summary", t)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals(450 to 1333, dev.taps[0])
        assertEquals("chat_pending", dev.screen)
    }

    @Test
    fun noRaidMeansNoTaps() = runTest {
        val dev = FakeDevice("chat_pending", t)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertTrue(dev.taps.isEmpty())
    }

    @Test
    fun guardStopsWithoutTapping() = runTest {
        val seq = Sequence("g", null, mutableListOf(ClickStep(515, 1505)))
        // «Pantalla prohibida» = botón verde grande del overview (como si fuera de pago).
        seq.guards += Guard("prueba", mutableListOf(ColorCondition(200, 1470, 840, 1540, "#88d828,#78c828", 24, 0.6)), null)
        val dev = FakeDevice("overview_join", t)
        try {
            Player(seq, dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
            fail("debía frenar")
        } catch (_: StopSequence) {
        }
        assertTrue(dev.taps.isEmpty())
    }
}
