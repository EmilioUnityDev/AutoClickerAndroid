package io.github.emiliounitydev.autoclicker.ui

import android.content.Context
import io.github.emiliounitydev.autoclicker.model.Sequence
import io.github.emiliounitydev.autoclicker.model.Step
import java.io.File

/** Secuencias guardadas como JSON en el almacenamiento interno de la app. */
class SequenceStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "sequences").apply { mkdirs() }

    fun names(): List<String> = dir.listFiles { f -> f.extension == "json" }
        ?.map { it.nameWithoutExtension }?.sortedBy { it.lowercase() } ?: emptyList()

    fun file(name: String) = File(dir, "${safe(name)}.json")

    fun load(name: String): Sequence = Sequence.parse(file(name).readText())

    fun save(name: String, seq: Sequence) {
        seq.name = name
        file(name).writeText(seq.toJson().toString(1))
    }

    fun delete(name: String) = file(name).delete()

    fun rename(old: String, new: String): Boolean {
        val seq = load(old)
        save(new, seq)
        if (safe(old) != safe(new)) delete(old)
        return true
    }

    /** Nombre libre a partir de `base` («base», «base (2)», …). */
    fun freeName(base: String): String {
        var n = safe(base).ifBlank { "secuencia" }
        var i = 2
        val root = n
        while (file(n).exists()) n = "$root ($i)".also { i++ }
        return n
    }

    companion object {
        fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

        /** Ruta: [i0, rama1, i1, rama2, i2…] (rama = índice en childLists()). */
        fun stepAt(seq: Sequence, path: IntArray): Step {
            var s = seq.steps[path[0]]
            var i = 1
            while (i + 1 < path.size) {
                s = s.childLists()[path[i]].second[path[i + 1]]
                i += 2
            }
            return s
        }
    }
}
