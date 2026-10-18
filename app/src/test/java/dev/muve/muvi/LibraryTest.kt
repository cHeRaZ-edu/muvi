package dev.muve.muvi

import dev.muve.muvi.data.Catalog
import dev.muve.muvi.data.Positions
import dev.muve.muvi.data.Video
import dev.muve.muvi.data.browse
import dev.muve.muvi.data.cleanFolder
import dev.muve.muvi.data.fmtTime
import dev.muve.muvi.data.humanSize
import dev.muve.muvi.data.isUploadable
import dev.muve.muvi.data.isVideo
import dev.muve.muvi.data.search
import dev.muve.muvi.data.subtitleLanguage
import dev.muve.muvi.data.videosUnder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun v(p: String) = Video(p, 1, 1000, true, "")
    private val lib = listOf(
        v("videos/Viajes/playa.mkv"), v("videos/Viajes/sierra.mp4"), v("videos/Películas/Acción ñandú.webm"),
        v("videos/suelto.avi"), v("raiz.mov"),
    )

    @Test fun browseRaiz() {
        val (folders, videos) = browse(lib, "")
        assertEquals(listOf("videos"), folders.map { it.name })
        assertEquals(4, folders[0].count)
        assertEquals(listOf("raiz.mov"), videos.map { it.path })
    }

    @Test fun browseCarpeta() {
        val (folders, videos) = browse(lib, "videos/")
        assertEquals(listOf("Películas", "Viajes"), folders.map { it.name })
        assertEquals(listOf("videos/suelto.avi"), videos.map { it.path })
        assertEquals(2, videosUnder(lib, "videos/Viajes/").size)
    }

    @Test fun buscaSinAcentosNiMayusculas() {
        assertEquals(listOf("videos/Películas/Acción ñandú.webm"), search(lib, "accion NANDU").map { it.path })
        assertTrue(search(lib, "   ").isEmpty())
    }

    @Test fun formatos() {
        assertTrue(isVideo("a/b.MKV"))
        assertTrue(isVideo("a/b.m2ts"))
        assertFalse(isVideo("a/b.mp3"))
        assertTrue(isUploadable("peli.es.srt"))
        assertFalse(isUploadable("notas.txt"))
        assertTrue(v("x.wmv").maybeUnsupported)
        assertFalse(v("x.mp4").maybeUnsupported)
    }

    @Test fun subtitulosDelMismoNombre() {
        val peli = v("videos/peli.mkv")
        val cat = Catalog(
            listOf(peli, v("videos/peli2.mkv")),
            listOf(v("videos/peli.srt"), v("videos/peli.es.ass"), v("videos/peli2.srt"), v("otra/peli.srt")),
        )
        assertEquals(listOf("videos/peli.srt", "videos/peli.es.ass"), cat.subtitlesFor(peli).map { it.path })
        assertEquals("es", subtitleLanguage(peli, v("videos/peli.es.ass")))
        assertNull(subtitleLanguage(peli, v("videos/peli.srt")))
    }

    @Test fun carpetasLimpias() {
        assertEquals("a/b/", cleanFolder(" a / b /"))
        assertEquals("", cleanFolder("///"))
        assertNull(cleanFolder("a/../b"))
    }

    @Test fun seguirDondeIbas() {
        assertFalse(Positions.shouldKeep(5_000, 600_000)) // apenas empezaba
        assertTrue(Positions.shouldKeep(120_000, 600_000))
        assertFalse(Positions.shouldKeep(595_000, 600_000)) // ya casi terminaba
        assertTrue(Positions.shouldKeep(120_000, 0)) // duracion desconocida
    }

    @Test fun textos() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.5 MB", humanSize(1572864))
        assertEquals("1:05", fmtTime(65_000))
        assertEquals("1:01:01", fmtTime(3_661_000))
        assertEquals("playa", v("videos/Viajes/playa.mkv").title)
    }
}
