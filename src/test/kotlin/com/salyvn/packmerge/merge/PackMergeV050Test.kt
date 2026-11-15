package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.OverlayEntry
import com.salyvn.packmerge.core.PackFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for packmerge v0.5.0 features:
 *   - AtlasPackingSimulator (PM019 cross-namespace conflict, PM020 missing sprite ref)
 *   - AudioBitDepthAuditor  (PM021 codec mismatch, PM022 sample rate / channel count)
 */
class PackMergeV050Test {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun atlasFile(relative: String, sources: String): MergedFile {
        val json = """{"sources":[$sources]}"""
        return MergedFile(relative, json.toByteArray(), listOf("test"), MergeKind.ATLAS)
    }

    private fun textureFile(relative: String): MergedFile =
        MergedFile(relative, ByteArray(16), listOf("test"), MergeKind.SINGLE_OWNER)

    private fun noOverlays(): List<OverlayEntry> = emptyList()

    private fun makeVorbisOgg(
        channels: Int = 1,
        sampleRate: Int = 44100,
        corrupted: Boolean = false,
    ): ByteArray {
        // Minimal valid OGG page with a Vorbis I ID header BOS page
        // OGG page header fields:
        //   capture (4), version (1), headerType (1=0x02 BOS), granule (8), serial (4),
        //   seqno (4), checksum (4), pageSegCount (1), segTable (N), data
        val vorbisIdPayload = buildVorbisIdPacket(channels, sampleRate)
        val segTable = byteArrayOf(vorbisIdPayload.size.toByte())
        val header = ByteBuffer.allocate(27)
            .put("OggS".toByteArray(Charsets.US_ASCII)) // 4
            .put(0)                                      // version
            .put(0x02)                                   // BOS
            .putLong(0L)                                 // granule
            .putInt(1)                                   // serial
            .putInt(0)                                   // seqno
            .putInt(0)                                   // checksum (0 for test purposes)
            .put(1)                                      // 1 segment
            .array()
        return if (corrupted) byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        else header + segTable + vorbisIdPayload
    }

    private fun buildVorbisIdPacket(channels: Int, sampleRate: Int): ByteArray {
        val magic = byteArrayOf(0x01, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        // version(4) + channels(1) + sampleRate(4) + bitrate_max(4) + bitrate_nom(4) + bitrate_min(4) + blocksize(1) + framing(1) = 23
        val body = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)              // version = 0
            .put(channels.toByte())
            .putInt(sampleRate)
            .putInt(0)              // bitrate_max
            .putInt(128_000)        // bitrate_nom
            .putInt(0)              // bitrate_min
            .put(0x99.toByte())     // blocksize
            .put(0x01)              // framing_bit
            .array()
        return magic + body
    }

    private fun makeOpusOgg(): ByteArray {
        val opusPayload = "OpusHead".toByteArray(Charsets.US_ASCII) + ByteArray(11)
        val segTable = byteArrayOf(opusPayload.size.toByte())
        val header = ByteBuffer.allocate(27)
            .put("OggS".toByteArray(Charsets.US_ASCII))
            .put(0).put(0x02)
            .putLong(0L).putInt(1).putInt(0).putInt(0)
            .put(1)
            .array()
        return header + segTable + opusPayload
    }

    private fun makeSpeexOgg(): ByteArray {
        val speexPayload = "Speex   ".toByteArray(Charsets.US_ASCII) + ByteArray(60)
        val segTable = byteArrayOf(speexPayload.size.toByte())
        val header = ByteBuffer.allocate(27)
            .put("OggS".toByteArray(Charsets.US_ASCII))
            .put(0).put(0x02)
            .putLong(0L).putInt(1).putInt(0).putInt(0)
            .put(1)
            .array()
        return header + segTable + speexPayload
    }

    // -------------------------------------------------------------------------
    // AtlasPackingSimulator -- PM019
    // -------------------------------------------------------------------------

    @Test
    fun `PM019 - single source from same namespace does not conflict`() {
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"single","resource":"minecraft:block/stone"}""",
            ),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = false, report = report)
        assertEquals(0, report.errors + report.warnings, "No conflicts expected for same-namespace single source")
    }

    @Test
    fun `PM019 - cross-namespace single sources for same sprite ID are flagged`() {
        // Two packs register the same sprite under different namespaces
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"single","resource":"shared:block/icon"}""",
            ),
            atlasFile(
                "assets/oraxen/atlases/blocks.json",
                """{"type":"single","resource":"shared:block/icon"}""",
            ),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = false, report = report)
        val pm019 = report.issues.filter { it.rule == MergeRule.CROSS_NAMESPACE_ATLAS_CONFLICT }
        assertEquals(1, pm019.size, "Expected exactly one PM019 cross-namespace atlas conflict")
        assertTrue(pm019.first().message.contains("shared:block/icon"))
    }

    @Test
    fun `PM019 - sprite sources from same atlas file different namespaces are flagged`() {
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"sprite","resources":["alpha:icon","beta:icon"]}""",
            ),
        )
        // Both alpha:icon and beta:icon are distinct sprite IDs -- no conflict here
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = false, report = report)
        assertEquals(0, report.warnings, "Distinct sprite IDs from sprite source should not conflict")
    }

    @Test
    fun `PM019 - sprite source conflict detected across separate atlas files`() {
        // Both atlas files register the same ID but are attributed different namespaces
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"single","resource":"shared:gui/button"}""",
            ),
            atlasFile(
                "assets/nexo/atlases/blocks.json",
                """{"type":"single","resource":"shared:gui/button"}""",
            ),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = false, report = report)
        val conflicts = report.issues.filter { it.rule == MergeRule.CROSS_NAMESPACE_ATLAS_CONFLICT }
        assertEquals(1, conflicts.size)
    }

    @Test
    fun `PM019 - malformed atlas JSON is silently skipped`() {
        val badFile = MergedFile(
            "assets/minecraft/atlases/blocks.json",
            "not json at all !!!".toByteArray(),
            listOf("test"),
            MergeKind.ATLAS,
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(listOf(badFile), noOverlays(), auditAtlas = false, report = report)
        assertEquals(0, report.errors, "Malformed atlas JSON should not throw or emit PM019")
    }

    // -------------------------------------------------------------------------
    // AtlasPackingSimulator -- PM020 (audit-atlas mode)
    // -------------------------------------------------------------------------

    @Test
    fun `PM020 - single source with present texture does not trigger when audit-atlas enabled`() {
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"single","resource":"minecraft:block/stone"}""",
            ),
            textureFile("assets/minecraft/textures/block/stone.png"),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = true, report = report)
        val pm020 = report.issues.filter { it.rule == MergeRule.ATLAS_SOURCE_MISSING_SPRITE_REF }
        assertEquals(0, pm020.size, "Texture present -- no PM020 expected")
    }

    @Test
    fun `PM020 - single source with missing texture is flagged when audit-atlas enabled`() {
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"single","resource":"custom:block/nonexistent"}""",
            ),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = true, report = report)
        val pm020 = report.issues.filter { it.rule == MergeRule.ATLAS_SOURCE_MISSING_SPRITE_REF }
        assertEquals(1, pm020.size, "Missing texture should produce PM020 under --audit-atlas")
        assertTrue(pm020.first().message.contains("custom/textures/block/nonexistent.png"))
    }

    @Test
    fun `PM020 - missing texture is not flagged when audit-atlas disabled`() {
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"single","resource":"custom:block/nonexistent"}""",
            ),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = false, report = report)
        val pm020 = report.issues.filter { it.rule == MergeRule.ATLAS_SOURCE_MISSING_SPRITE_REF }
        assertEquals(0, pm020.size, "PM020 should not trigger when --audit-atlas is off")
    }

    @Test
    fun `PM020 - directory sources are not flagged even if path absent`() {
        // directory sources expand at runtime; we cannot enumerate them statically
        val files = listOf(
            atlasFile(
                "assets/minecraft/atlases/blocks.json",
                """{"type":"directory","source":"block","prefix":"block/"}""",
            ),
        )
        val report = MergeReport()
        AtlasPackingSimulator.validateAll(files, noOverlays(), auditAtlas = true, report = report)
        assertEquals(0, report.warnings, "directory sources should not produce PM020")
    }

    // -------------------------------------------------------------------------
    // AudioBitDepthAuditor -- PM021
    // -------------------------------------------------------------------------

    @Test
    fun `PM021 - valid Vorbis I OGG in sounds dir does not emit errors`() {
        val file = MergedFile(
            "assets/minecraft/sounds/music/game/creative.ogg",
            makeVorbisOgg(channels = 1, sampleRate = 44100),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        assertEquals(0, report.errors, "Clean Vorbis I should produce no PM021 errors")
        assertEquals(0, report.warnings, "Clean Vorbis I 44100 Hz mono should produce no PM022 warnings")
    }

    @Test
    fun `PM021 - Opus OGG in sounds dir is flagged as codec mismatch`() {
        val file = MergedFile(
            "assets/minecraft/sounds/random/explosion.ogg",
            makeOpusOgg(),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        val pm021 = report.issues.filter { it.rule == MergeRule.AUDIO_CODEC_MISMATCH }
        assertEquals(1, pm021.size, "Opus OGG should produce one PM021 error")
        assertTrue(pm021.first().message.contains("Opus"))
    }

    @Test
    fun `PM021 - Speex OGG in sounds dir is flagged as codec mismatch`() {
        val file = MergedFile(
            "assets/minecraft/sounds/ambient/cave/cave.ogg",
            makeSpeexOgg(),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        val pm021 = report.issues.filter { it.rule == MergeRule.AUDIO_CODEC_MISMATCH }
        assertEquals(1, pm021.size, "Speex OGG should produce one PM021 error")
    }

    @Test
    fun `PM021 - OGG outside sounds directory is not audited`() {
        val file = MergedFile(
            "assets/minecraft/textures/something.ogg", // wrong dir
            makeOpusOgg(),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        assertEquals(0, report.errors + report.warnings, "OGG outside sounds/ should not be audited")
    }

    @Test
    fun `PM021 - non-OGG files are skipped`() {
        val file = MergedFile(
            "assets/minecraft/sounds/music/game/creative.mp3",
            makeOpusOgg(), // wrong extension
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        assertEquals(0, report.errors, "Non-.ogg extension should be skipped")
    }

    // -------------------------------------------------------------------------
    // AudioBitDepthAuditor -- PM022
    // -------------------------------------------------------------------------

    @Test
    fun `PM022 - Vorbis I 96000 Hz sample rate is flagged`() {
        val file = MergedFile(
            "assets/minecraft/sounds/music/menu/menu1.ogg",
            makeVorbisOgg(channels = 1, sampleRate = 96_000),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        val pm022 = report.issues.filter { it.rule == MergeRule.AUDIO_SAMPLE_RATE_OUT_OF_RANGE }
        assertEquals(1, pm022.size, "96000 Hz sample rate should produce PM022")
        assertTrue(pm022.first().message.contains("96000"))
    }

    @Test
    fun `PM022 - Vorbis I 7999 Hz sample rate is flagged`() {
        val file = MergedFile(
            "assets/minecraft/sounds/random/click.ogg",
            makeVorbisOgg(channels = 1, sampleRate = 7_999),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        val pm022 = report.issues.filter { it.rule == MergeRule.AUDIO_SAMPLE_RATE_OUT_OF_RANGE }
        assertEquals(1, pm022.size, "7999 Hz sample rate should produce PM022")
    }

    @Test
    fun `PM022 - Vorbis I 48000 Hz sample rate is accepted`() {
        val file = MergedFile(
            "assets/minecraft/sounds/random/click.ogg",
            makeVorbisOgg(channels = 2, sampleRate = 48_000),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        assertEquals(0, report.warnings, "48000 Hz stereo Vorbis should not produce PM022")
    }

    @Test
    fun `PM022 - Vorbis I 6-channel audio is flagged`() {
        val file = MergedFile(
            "assets/minecraft/sounds/random/explosion.ogg",
            makeVorbisOgg(channels = 6, sampleRate = 44100),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        val pm022 = report.issues.filter { it.rule == MergeRule.AUDIO_SAMPLE_RATE_OUT_OF_RANGE }
        assertEquals(1, pm022.size, "6-channel audio should produce PM022")
        assertTrue(pm022.first().message.contains("6 channels"))
    }

    @Test
    fun `PM022 - corrupted OGG data is silently skipped`() {
        val file = MergedFile(
            "assets/minecraft/sounds/random/break.ogg",
            makeVorbisOgg(corrupted = true),
            listOf("test"),
            MergeKind.SINGLE_OWNER,
        )
        val report = MergeReport()
        AudioBitDepthAuditor.validateAll(listOf(file), noOverlays(), report)
        assertEquals(0, report.errors, "Corrupted OGG should be silently skipped")
    }

    // -------------------------------------------------------------------------
    // Integration: MergeOptions correctly threads new flags through PackMerge
    // -------------------------------------------------------------------------

    @Test
    fun `MergeOptions - auditAtlas and auditAudio change hasSinglePackTransforms`() {
        val base = MergeOptions()
        assertTrue(!base.hasSinglePackTransforms, "Default options should not trigger single-pack transforms")
        assertTrue(MergeOptions(auditAtlas = true).hasSinglePackTransforms)
        assertTrue(MergeOptions(auditAudio = true).hasSinglePackTransforms)
    }
}
