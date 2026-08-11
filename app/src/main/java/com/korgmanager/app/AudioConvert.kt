package com.korgmanager.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Résultat d'un décodage audio : PCM 16 bits entrelacé. */
data class PcmAudio(val samples: ShortArray, val sampleRate: Int, val channels: Int) {
    val frames: Int get() = if (channels == 0) 0 else samples.size / channels
    val durationSec: Double get() = if (sampleRate == 0) 0.0 else frames.toDouble() / sampleRate
}

/**
 * Décode n'importe quel format audio supporté par Android (MP3, WAV, FLAC,
 * AAC, OGG...) en PCM, rééchantillonne au format ES-1 (32 000 Hz) et
 * réécrit un fichier WAV standard.
 */
object AudioConvert {

    const val ES1_RATE = 32000

    /** Décode un fichier audio (chemin local) en PCM 16 bits. */
    fun decode(path: String): PcmAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IOException("Pas de piste audio dans ce fichier")
        }
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        extractor.selectTrack(trackIndex)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var guard = 0
        while (!outputDone && guard++ < 200000) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10000)
            when {
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk)
                        pcm.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        val bytes = pcm.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        if (shorts.isEmpty()) throw IOException("Décodage vide (format non supporté ?)")
        return PcmAudio(shorts, sampleRate, channels)
    }

    /** Rééchantillonnage linéaire vers un autre taux (par canal, entrelacé). */
    fun resample(audio: PcmAudio, toRate: Int): PcmAudio {
        if (audio.sampleRate == toRate) return audio
        val ch = audio.channels
        val inFrames = audio.frames
        val outFrames = (inFrames.toLong() * toRate / audio.sampleRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * ch)
        val ratio = audio.sampleRate.toDouble() / toRate
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            for (c in 0 until ch) {
                val s0 = audio.samples[i0 * ch + c].toDouble()
                val s1 = audio.samples[i1 * ch + c].toDouble()
                out[i * ch + c] = (s0 + (s1 - s0) * frac).toInt()
                    .coerceIn(-32768, 32767).toShort()
            }
        }
        return PcmAudio(out, toRate, ch)
    }

    /** Normalise le volume (pic à ~95 %). */
    fun normalize(audio: PcmAudio): PcmAudio {
        var peak = 0
        for (s in audio.samples) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > peak) peak = a
        }
        if (peak == 0 || peak >= 31000) return audio
        val gain = 31000.0 / peak
        val out = ShortArray(audio.samples.size)
        for (i in audio.samples.indices) {
            out[i] = (audio.samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return PcmAudio(out, audio.sampleRate, audio.channels)
    }

    /** Construit un fichier WAV 16 bits PCM standard. */
    fun toWav(audio: PcmAudio): ByteArray {
        val dataSize = audio.samples.size * 2
        val out = ByteArray(44 + dataSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(audio.channels.toShort())
        bb.putInt(audio.sampleRate)
        bb.putInt(audio.sampleRate * audio.channels * 2)
        bb.putShort((audio.channels * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataSize)
        for (s in audio.samples) bb.putShort(s)
        return out
    }
}
