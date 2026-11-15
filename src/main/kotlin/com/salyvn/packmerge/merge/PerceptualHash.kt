package com.salyvn.packmerge.merge

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ImageFingerprint(
    val width: Int,
    val height: Int,
    val pixelSha256: String,
    val phash: ULong,
    val isSolidColor: Boolean,
    val meanArgb: Int,
) {
    val phashHex: String get() = "0x" + phash.toString(16).padStart(16, '0')
}

/**
 * Pure-JVM PNG pixel digest and 64-bit 2D Discrete Cosine Transform (DCT-II) perceptual image hash (`pHash`).
 *
 * Minecraft resource packs frequently re-export identical 16x16 or 32x32 item textures across Blockbench,
 * Aseprite, and oxipng with different zlib streams, filter bytes, or stripped `tEXt`/`iCCP` chunks.
 * [inspect] computes both a metadata-invariant decoded RGBA SHA-256 digest and a 64-bit DCT perceptual hash.
 */
object PerceptualHash {
    private const val DCT_SIZE = 32
    private const val HASH_DIM = 8
    private const val SOLID_AC_EPSILON = 1e-4

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    /** Orthonormal 32x32 DCT-II basis matrix `C[u][x]`. */
    private val DCT_BASIS: Array<DoubleArray> = Array(DCT_SIZE) { u ->
        val scale = if (u == 0) sqrt(1.0 / DCT_SIZE) else sqrt(2.0 / DCT_SIZE)
        DoubleArray(DCT_SIZE) { x ->
            scale * cos(((2.0 * x + 1.0) * u * PI) / (2.0 * DCT_SIZE))
        }
    }

    init {
        ImageIO.setUseCache(false)
    }

    fun isPngBytes(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    fun inspect(bytes: ByteArray): ImageFingerprint? {
        if (!isPngBytes(bytes)) return null
        val image: BufferedImage = try {
            ByteArrayInputStream(bytes).use { ImageIO.read(it) }
        } catch (_: Exception) {
            null
        } ?: return null

        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null

        val argb = IntArray(w * h)
        image.getRGB(0, 0, w, h, argb, 0, w)

        val sha = MessageDigest.getInstance("SHA-256")
        val header = ByteBuffer.allocate(8).putInt(w).putInt(h).array()
        sha.update(header)

        var sumA = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        val rowBuf = ByteBuffer.allocate(w * 4)

        for (y in 0 until h) {
            rowBuf.clear()
            val rowOffset = y * w
            for (x in 0 until w) {
                val px = argb[rowOffset + x]
                val a = (px ushr 24) and 0xFF
                // Normalize fully transparent pixels so invisible RGB values under alpha=0 never skew pixel identity.
                val normalized = if (a == 0) 0 else px
                rowBuf.putInt(normalized)
                sumA += a
                if (a > 0) {
                    sumR += ((px ushr 16) and 0xFF) * a / 255
                    sumG += ((px ushr 8) and 0xFF) * a / 255
                    sumB += (px and 0xFF) * a / 255
                }
            }
            sha.update(rowBuf.array())
        }
        val pixelSha256 = sha.digest().joinToString("") { "%02x".format(it) }

        val totalPixels = (w.toLong() * h.toLong()).coerceAtLeast(1L)
        val meanA = (sumA / totalPixels).toInt().coerceIn(0, 255)
        val meanR = (sumR / totalPixels).toInt().coerceIn(0, 255)
        val meanG = (sumG / totalPixels).toInt().coerceIn(0, 255)
        val meanB = (sumB / totalPixels).toInt().coerceIn(0, 255)
        val meanArgb = (meanA shl 24) or (meanR shl 16) or (meanG shl 8) or meanB

        val grid = resampleLuminance32(argb, w, h)
        val dct = computeDct8x9(grid)

        // Collect 64 pure AC coefficients: (u in 0..7, v in 0..7) with DC (0,0) replaced by (0,8).
        val ac = DoubleArray(64)
        var idx = 0
        var maxAcAbs = 0.0
        for (u in 0 until HASH_DIM) {
            for (v in 0 until HASH_DIM) {
                val coeff = if (u == 0 && v == 0) dct[0][HASH_DIM] else dct[u][v]
                ac[idx++] = coeff
                val mag = abs(coeff)
                if (mag > maxAcAbs) maxAcAbs = mag
            }
        }

        if (maxAcAbs < SOLID_AC_EPSILON) {
            val packedColor = meanArgb.toUInt().toULong()
            val solidHash = (0x534F4C49UL shl 32) or packedColor
            return ImageFingerprint(
                width = w,
                height = h,
                pixelSha256 = pixelSha256,
                phash = solidHash,
                isSolidColor = true,
                meanArgb = meanArgb,
            )
        }

        val sorted = ac.copyOf().also { it.sort() }
        val median = 0.5 * (sorted[31] + sorted[32])
        var bits = 0UL
        for (i in 0 until 64) {
            if (ac[i] > median) {
                bits = bits or (1UL shl i)
            }
        }

        return ImageFingerprint(
            width = w,
            height = h,
            pixelSha256 = pixelSha256,
            phash = bits,
            isSolidColor = false,
            meanArgb = meanArgb,
        )
    }

    /**
     * Perceptual Hamming distance in `[0, 64]`. Identical decoded RGBA pixels always have distance `0`.
     */
    fun distance(a: ImageFingerprint, b: ImageFingerprint): Int {
        if (a.pixelSha256 == b.pixelSha256) return 0
        if (a.isSolidColor || b.isSolidColor) {
            if (a.isSolidColor != b.isSolidColor) return 64
            val da = abs(((a.meanArgb ushr 24) and 0xFF) - ((b.meanArgb ushr 24) and 0xFF))
            val dr = abs(((a.meanArgb ushr 16) and 0xFF) - ((b.meanArgb ushr 16) and 0xFF))
            val dg = abs(((a.meanArgb ushr 8) and 0xFF) - ((b.meanArgb ushr 8) and 0xFF))
            val db = abs((a.meanArgb and 0xFF) - (b.meanArgb and 0xFF))
            if (da <= 4 && dr <= 4 && dg <= 4 && db <= 4) return 0
            return ((da + dr + dg + db) / 16).coerceIn(1, 64)
        }
        return (a.phash xor b.phash).countOneBits()
    }

    /**
     * Area-average / box-filter resampling onto a `32x32` premultiplied Rec.601 luminance matrix so both
     * upscaling (`16x16 -> 32x32`) and downscaling (`64x64 -> 32x32`) preserve spatial energy cleanly.
     */
    private fun resampleLuminance32(argb: IntArray, width: Int, height: Int): Array<DoubleArray> {
        val out = Array(DCT_SIZE) { DoubleArray(DCT_SIZE) }
        val scaleX = width.toDouble() / DCT_SIZE
        val scaleY = height.toDouble() / DCT_SIZE

        for (gy in 0 until DCT_SIZE) {
            val y0 = gy * scaleY
            val y1 = (gy + 1) * scaleY
            val iyStart = y0.toInt().coerceIn(0, height - 1)
            val iyEnd = min(height - 1, max(iyStart, kotlin.math.ceil(y1 - 1e-9).toInt()))

            for (gx in 0 until DCT_SIZE) {
                val x0 = gx * scaleX
                val x1 = (gx + 1) * scaleX
                val ixStart = x0.toInt().coerceIn(0, width - 1)
                val ixEnd = min(width - 1, max(ixStart, kotlin.math.ceil(x1 - 1e-9).toInt()))

                var weightedSum = 0.0
                var weightTotal = 0.0
                for (py in iyStart..iyEnd) {
                    val wy = (min(y1, (py + 1).toDouble()) - max(y0, py.toDouble())).coerceAtLeast(0.0)
                    if (wy <= 0.0) continue
                    val rowOffset = py * width
                    for (px in ixStart..ixEnd) {
                        val wx = (min(x1, (px + 1).toDouble()) - max(x0, px.toDouble())).coerceAtLeast(0.0)
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val c = argb[rowOffset + px]
                        val a = ((c ushr 24) and 0xFF) / 255.0
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = a * (0.299 * r + 0.587 * g + 0.114 * b)
                        weightedSum += lum * w
                        weightTotal += w
                    }
                }
                out[gy][gx] = if (weightTotal > 0.0) (weightedSum / weightTotal) else 0.0
            }
        }
        return out
    }

    /** Computes the top `8 x 9` block of `C * grid * C^T`. */
    private fun computeDct8x9(grid: Array<DoubleArray>): Array<DoubleArray> {
        val temp = Array(HASH_DIM) { DoubleArray(DCT_SIZE) }
        for (u in 0 until HASH_DIM) {
            val cu = DCT_BASIS[u]
            for (x in 0 until DCT_SIZE) {
                var sum = 0.0
                for (y in 0 until DCT_SIZE) {
                    sum += cu[y] * grid[y][x]
                }
                temp[u][x] = sum
            }
        }

        val dct = Array(HASH_DIM) { DoubleArray(HASH_DIM + 1) }
        for (u in 0 until HASH_DIM) {
            val row = temp[u]
            for (v in 0..HASH_DIM) {
                val cv = DCT_BASIS[v]
                var sum = 0.0
                for (x in 0 until DCT_SIZE) {
                    sum += row[x] * cv[x]
                }
                dct[u][v] = (sum * 1e6).roundToInt() / 1e6
            }
        }
        return dct
    }
}
