package com.clashplayer.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTest {
    private fun t(id: Long, file: String) = Track(id, file, "A", "B", 1000, Uri.parse("content://x/$id"), fileName = file)

    @Test fun m3uMatchesByFileNameInOrder() {
        val lib = listOf(t(1, "01 Intro.flac"), t(2, "02 Song Two.flac"), t(3, "Track.dsf"))
        val m3u = "#EXTM3U\n#EXTINF:100,A - Two\nC:\\Music\\Album\\02 Song Two.flac\n/sdcard/Music/track.DSF\nmissing.mp3\n../01%20Intro.flac\n"
        val (ids, missing) = LocalMediaRepository.matchM3u(m3u, lib)
        assertEquals(listOf(2L, 3L, 1L), ids)
        assertEquals(1, missing)
    }

    @Test fun formatsAndIds() {
        assertEquals("DSD", LocalMediaRepository.formatOf("a.dff", null))
        assertEquals("FLAC", LocalMediaRepository.formatOf("a.flac", "audio/flac"))
        val id = LocalMediaRepository.stableId(Uri.parse("content://tree/doc/1"))
        assertTrue(id < 0)
        assertEquals(id, LocalMediaRepository.stableId(Uri.parse("content://tree/doc/1")))
    }
}
