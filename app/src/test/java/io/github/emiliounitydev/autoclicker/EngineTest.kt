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
    fun anchorsPerElement() {
        // Redmi Note 15 Pro: el juego escala por el ancho; sobran ≈496 px de alto.
        val m = Mapping.Mapper(Screen(900, 1600), Screen(1280, 2772), Mapping.Mode.ANCHOR)
        assertEquals(1905, m.y(1165, 0.5))   // chat (centrado)
        assertEquals(2637, m.y(1505, 1.0))   // overview (abajo)
        assertEquals(142, m.y(100, 0.0))     // arriba
        for (a in listOf(0.0, 0.5, 0.66, 1.0)) assertEquals(1234.0, m.invY(m.y(1234, a), a).toDouble(), 1.0)
        val c = m.cond(ColorCondition(170, 80, 860, 160, "#484878", 16, 0.6, anchorY = 0.5))
        assertEquals(362, c.y1); assertEquals(0.5, c.anchorY!!, 0.0)
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
private class FakeDevice(
    var screen: String,
    val transitions: Map<String, List<Pair<IntArray, String>>>,
    override val screenWidth: Int = 900,
    override val screenHeight: Int = 1600,
) : DeviceIO {
    val frames = HashMap<String, Frame>()
    val taps = mutableListOf<Pair<Int, Int>>()
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
    fun anchorsSurviveJsonRoundTrip() {
        val seq = oneCycle()
        val again = Sequence.parse(seq.toJson().toString())
        var n = 0
        Sequence.walk(again.steps) { if (it is ClickStep && it.anchorY != null) n++ }
        assertTrue("toques con anclaje: $n", n >= 8)
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

/**
 * La misma secuencia de PC (900×1600) sobre capturas reales de un Redmi Note 15
 * Pro (1280×2772), sin mapear: el reproductor la adapta con los anclajes.
 */
class RaidJoinPhoneTest {
    private val w = 1280
    private val h = 2772
    private val t = mapOf(
        "phone_summary" to listOf(intArrayOf(500, 2180, 780, 2270) to "phone_chat_join"),
        "phone_chat_join" to listOf(intArrayOf(480, 1850, 1070, 1960) to "phone_overview_join"),
        // Sin captura de «Pending»: tras «Join» sigue el mismo overview.
        "phone_overview_join" to listOf(intArrayOf(20, 2570, 180, 2700) to "phone_map"),
        "phone_map" to listOf(intArrayOf(1140, 2190, 1260, 2320) to "phone_chat_pending"),
    )

    private fun oneCycle(): Sequence {
        val seq = Sequence.parse(res("raid/raid_join.json").bufferedReader().readText())
        (seq.steps[0] as SubsequenceStep).repeats = 1
        return seq
    }

    private suspend fun kotlinx.coroutines.test.TestScope.play(start: String): FakeDevice {
        val dev = FakeDevice(start, t, w, h)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        return dev
    }

    @Test
    fun fullCycleFromChat() = runTest {
        val dev = play("phone_chat_join")
        assertEquals("phone_chat_pending", dev.screen)
        assertEquals(listOf(775 to 1905, 732 to 2637, 100 to 2630, 1195 to 2253), dev.taps)
    }

    @Test
    fun summaryIsConfirmedFirst() = runTest {
        val dev = play("phone_summary")
        assertEquals(640 to 2223, dev.taps[0])
        assertEquals("phone_chat_pending", dev.screen)
    }

    @Test
    fun noRaidMeansNoTaps() = runTest {
        assertTrue(play("phone_chat_pending").taps.isEmpty())
    }

    @Test
    fun rallyListInsteadOfOverview() = runTest {
        // A veces «Join» en el chat abre la lista «Rally Attack»: su «Join» lleva al overview.
        val tl = t + mapOf(
            "phone_chat_join" to listOf(intArrayOf(480, 1850, 1070, 1960) to "phone_rally_list_join"),
            "phone_rally_list_join" to listOf(intArrayOf(830, 2200, 1160, 2290) to "phone_overview_join"),
        )
        val dev = FakeDevice("phone_chat_join", tl, w, h)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals(listOf(775 to 1905, 996 to 2260, 732 to 2637, 100 to 2630, 1195 to 2253), dev.taps)
        assertEquals("phone_chat_pending", dev.screen)
    }

    @Test
    fun rallyListWithoutJoinIsClosed() = runTest {
        // Lista sin «Join» visible: la desplaza 6 veces buscando más abajo y la cierra con la X.
        val tl = t + mapOf("phone_chat_join" to listOf(intArrayOf(480, 1850, 1070, 1960) to "phone_rally_list"))
        val dev = FakeDevice("phone_chat_join", tl, w, h)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals(775 to 1905, dev.taps[0])
        assertEquals(List(6) { 640 to 2345 }, dev.taps.subList(1, 7))
        assertEquals(640 to 2715, dev.taps[7])
    }

    @Test
    fun shortRallyListIsRecognised() = runTest {
        // Con pocas tarjetas el fondo de la lista se parecía al del resumen y no se reconocía.
        val tl = t + mapOf(
            "phone_chat_join" to listOf(intArrayOf(480, 1850, 1070, 1960) to "phone_rally_list_short"),
            "phone_rally_list_short" to listOf(intArrayOf(830, 1215, 1160, 1330) to "phone_overview_join"),
        )
        val dev = FakeDevice("phone_chat_join", tl, w, h)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals(996 to 1300, dev.taps[1])
        assertEquals("phone_chat_pending", dev.screen)
    }

    @Test
    fun mapTileInfoIsClosed() = runTest {
        // Ficha de casilla del mapa (un toque cayó en el mapa): cerrar con la X.
        val tl = t + mapOf("phone_map_tile" to listOf(intArrayOf(540, 2650, 740, 2772) to "phone_map"))
        val dev = FakeDevice("phone_map_tile", tl, w, h)
        Player(oneCycle(), dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals(640 to 2708, dev.taps.first())
        assertEquals("phone_chat_pending", dev.screen)
    }

    @Test
    fun remappedCopyBehavesTheSame() = runTest {
        val seq = oneCycle()
        Mapping.remap(seq, Screen(w, h), Mapping.Mode.ANCHOR, "android", "test")
        val dev = FakeDevice("phone_chat_join", t, w, h)
        Player(seq, dev, object : PlayerListener {}, clock = { testScheduler.currentTime }).run()
        assertEquals(listOf(775 to 1905, 732 to 2637, 100 to 2630, 1195 to 2253), dev.taps)
    }
}
