package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.OverlayEntry
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minecraft resource pack `.ogg` audio bit-depth and codec diagnostics (PM021 / PM022).
 *
 * Minecraft uses the libvorbis decoder exclusively. OGG containers carrying non-Vorbis streams
 * (Opus, FLAC, Speex) silently fail to load. Very high channel counts (> 2) and sample rates
 * outside the range the game resamples without distortion (8 000..48 000 Hz) are flagged.
 *
 * Rules:
 *   PM021 - AudioCodecMismatch        : OGG container uses a codec other than Vorbis I.
 *   PM022 - AudioSampleRateOutOfRange : Vorbis stream declares a sample rate < 8 000 Hz or
 *                                       > 48 000 Hz, or carries more than 2 channels.
 */
object AudioBitDepthAuditor {

    // Vorbis I identification header magic: packet type 0x01 followed by "vorbis"
    private val VORBIS_MAGIC = byteArrayOf(0x01, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
    // Opus magic: "OpusHead"
    private val OPUS_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)
    // FLAC magic inside OGG: "\x7FFLAC"
    private val FLAC_MAGIC = byteArrayOf(0x7F, 'F'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte())
    // Speex magic: "Speex   "
    private val SPEEX_MAGIC = "Speex   ".toByteArray(Charsets.US_ASCII)

    private const val OGG_CAPTURE = "OggS"
    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 48_000
    private const val MAX_CHANNELS = 2

    /**
     * Scans all `.ogg` files in [files] and reports PM021/PM022 issues for non-Vorbis codecs and
     * out-of-range audio parameters.
     */
    fun validateAll(
        files: List<MergedFile>,
        overlays: List<OverlayEntry>,
        report: MergeReport,
    ) {
        val overlayDirs = overlays.map { it.directory }.toSet()
        for (file in files) {
            if (!isAudioFile(file.relative, overlayDirs)) continue
            try {
                diagnose(file, report)
            } catch (_: Exception) {
                // Malformed OGG: not parseable; skip to avoid false positives
            }
        }
    }

    private fun isAudioFile(relative: String, overlayDirs: Set<String>): Boolean {
        val lower = relative.lowercase()
        if (!lower.endsWith(".ogg")) return false
        // Must be under assets/<ns>/sounds/ or <overlay>/assets/<ns>/sounds/
        val parts = relative.split('/')
        return when {
            parts.size >= 4 && parts[0] == "assets" && parts[2] == "sounds" -> true
            parts.size >= 5 && parts[0] in overlayDirs && parts[1] == "assets" && parts[3] == "sounds" -> true
            else -> false
        }
    }

    /**
     * Parses OGG page headers looking for the first logical bitstream's identification packet header.
     * Returns the codec type as a string, and for Vorbis also validates sample rate and channels.
     */
    private fun diagnose(file: MergedFile, report: MergeReport) {
        val data = file.bytes
        if (data.size < 27) return

        // Walk OGG pages to find the first codec identification packet
        var offset = 0
        while (offset + 27 <= data.size) {
            // Check "OggS" capture pattern
            if (data[offset] != 'O'.code.toByte() ||
                data[offset + 1] != 'g'.code.toByte() ||
                data[offset + 2] != 'g'.code.toByte() ||
                data[offset + 3] != 'S'.code.toByte()
            ) {
                offset++
                continue
            }

            // header_type: 0x02 means beginning-of-stream (BOS page)
            val headerType = data[offset + 5].toInt() and 0xFF
            val pageSegCount = data[offset + 26].toInt() and 0xFF
            if (offset + 27 + pageSegCount > data.size) return

            val segTable = data.slice(offset + 27 until offset + 27 + pageSegCount)
            val pageDataStart = offset + 27 + pageSegCount
            val pageDataLen = segTable.sumOf { it.toInt() and 0xFF }
            if (pageDataStart + pageDataLen > data.size) return

            if (headerType and 0x02 != 0) {
                // BOS page: contains codec identification
                val pkt = data.slice(pageDataStart until pageDataStart + pageDataLen).toByteArray()
                val codec = detectCodec(pkt)
                when (codec) {
                    "vorbis" -> validateVorbisIdHeader(pkt, file.relative, report)
                    "opus" -> report.error(
                        file.relative,
                        "OGG container uses the Opus codec; Minecraft's libvorbis decoder cannot load it. Re-encode as Vorbis I.",
                        MergeRule.AUDIO_CODEC_MISMATCH,
                    )
                    "flac" -> report.error(
                        file.relative,
                        "OGG container uses FLAC-in-OGG; Minecraft's libvorbis decoder cannot load it. Re-encode as Vorbis I.",
                        MergeRule.AUDIO_CODEC_MISMATCH,
                    )
                    "speex" -> report.error(
                        file.relative,
                        "OGG container uses the Speex codec; Minecraft's libvorbis decoder cannot load it. Re-encode as Vorbis I.",
                        MergeRule.AUDIO_CODEC_MISMATCH,
                    )
                    else -> {} // Unknown codec; not flagged to avoid false positives on future codecs
                }
                // Only need first BOS page
                return
            }

            offset = pageDataStart + pageDataLen
        }
    }

    private fun detectCodec(packet: ByteArray): String? {
        if (packet.size >= VORBIS_MAGIC.size && packet.take(VORBIS_MAGIC.size).toByteArray().contentEquals(VORBIS_MAGIC)) return "vorbis"
        if (packet.size >= OPUS_MAGIC.size && packet.take(OPUS_MAGIC.size).toByteArray().contentEquals(OPUS_MAGIC)) return "opus"
        if (packet.size >= FLAC_MAGIC.size && packet.take(FLAC_MAGIC.size).toByteArray().contentEquals(FLAC_MAGIC)) return "flac"
        if (packet.size >= SPEEX_MAGIC.size && packet.take(SPEEX_MAGIC.size).toByteArray().contentEquals(SPEEX_MAGIC)) return "speex"
        return null
    }

    /**
     * Vorbis I identification header layout (after the 7-byte magic):
     *   - version       : 4 bytes LE, must be 0
     *   - channels      : 1 byte
     *   - sample_rate   : 4 bytes LE
     *   - bitrate_max   : 4 bytes LE
     *   - bitrate_nom   : 4 bytes LE
     *   - bitrate_min   : 4 bytes LE
     *   - blocksize      : 1 byte (packed)
     *   - framing_bit   : 1 byte
     */
    private fun validateVorbisIdHeader(packet: ByteArray, path: String, report: MergeReport) {
        // Need at least magic (7) + version (4) + channels (1) + sample_rate (4) = 16 bytes
        if (packet.size < 16) return
        val buf = ByteBuffer.wrap(packet, 7, packet.size - 7).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.int // 4 bytes
        if (version != 0) {
            report.warn(
                path,
                "Vorbis identification header declares non-zero version $version; this stream may not load correctly.",
                MergeRule.AUDIO_SAMPLE_RATE_OUT_OF_RANGE,
            )
        }
        if (buf.remaining() < 5) return
        val channels = buf.get().toInt() and 0xFF
        val sampleRate = buf.int
        if (channels > MAX_CHANNELS) {
            report.warn(
                path,
                "Vorbis stream declares $channels channels; Minecraft expects mono (1) or stereo (2) audio and may not load this correctly.",
                MergeRule.AUDIO_SAMPLE_RATE_OUT_OF_RANGE,
            )
        }
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            report.warn(
                path,
                "Vorbis stream declares sample rate ${sampleRate} Hz, outside the Minecraft-supported range of " +
                    "$MIN_SAMPLE_RATE..$MAX_SAMPLE_RATE Hz; the client may resample with audible distortion.",
                MergeRule.AUDIO_SAMPLE_RATE_OUT_OF_RANGE,
            )
        }
    }
}
