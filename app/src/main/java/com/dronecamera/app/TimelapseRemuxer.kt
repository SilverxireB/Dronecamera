package com.dronecamera.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Timelapse videosunu YENIDEN KODLAMADAN hizlandirir.
 *
 * Cekim sirasinda kareler zaten seyreltiliyor (10x hizda her onuncu kare
 * kaydediliyor) ama zaman damgalari gercek zamanli oldugu icin dosya uzun
 * kaliyor ve sonunda donmus kare olusuyordu. Burada dosya yalnizca yeniden
 * paketleniyor: ornekler oldugu gibi kopyalanip zaman damgalari [speed]
 * oraninda sikistiriliyor.
 *
 * Kod cozme/kodlama olmadigi icin islem saniyeler surer ve goruntu kalitesi
 * birebir korunur.
 */
object TimelapseRemuxer {

    /** Ornegin buyuk olma ihtimaline karsi tampon alt siniri (4K kareler icin). */
    private const val MIN_BUFFER_BYTES = 4 * 1024 * 1024

    /**
     * [input] dosyasini [outputFd] hedefine hizlandirilmis olarak yazar.
     * Basarisiz olursa false doner ve cagiran taraf ozgun dosyayi korur.
     */
    fun remux(input: File, outputFd: FileDescriptor, speed: Int): Boolean {
        if (speed <= 1 || !input.exists()) return false

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }

            var videoTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                if (trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    format = trackFormat
                    break
                }
            }
            val videoFormat = format ?: return false
            if (videoTrack < 0) return false
            extractor.selectTrack(videoTrack)

            muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            readRotation(input)?.let { muxer.setOrientationHint(it) }
            val outputTrack = muxer.addTrack(videoFormat)
            muxer.start()

            val bufferSize = maxOf(
                if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    0
                },
                MIN_BUFFER_BYTES
            )
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            var firstTimestamp = -1L
            var wroteSample = false

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTime = extractor.sampleTime
                if (firstTimestamp < 0L) firstTimestamp = sampleTime

                info.offset = 0
                info.size = size
                // Zaman damgasi sikistirilir; ornek verisine dokunulmaz.
                info.presentationTimeUs = firstTimestamp + (sampleTime - firstTimestamp) / speed
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                wroteSample = true
                extractor.advance()
            }
            wroteSample
        } catch (e: Exception) {
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun readRotation(file: File): Int? {
        // MediaMetadataRetriever yalnizca API 29+ AutoCloseable; elle kapatiyoruz.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?.takeIf { it != 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
