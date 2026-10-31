package com.example.exosys

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================
// 1. CONSTANTES FÍSICAS Y COMPILADOR V32 (ARQUITECTURA FSR 205x251)
// =============================================================================
object ExndV32Compiler {

    const val TARGET_W = 205
    const val TARGET_H = 251
    const val NUM_PIXELS = TARGET_W * TARGET_H
    const val TARGET_RATIO = 410f / 502f

    const val MAX_TARGET_MB = 7.4
    const val MAX_TARGET_BYTES = (MAX_TARGET_MB * 1024 * 1024).toInt()
    const val MAX_FRAME_DECODER_BYTES = 120000 // Límite duro de local_decoder_buf en PSRAM

    private val BAYER_LUT = FloatArray(NUM_PIXELS).apply {
        val b4 = arrayOf(
            floatArrayOf(0f, 8f, 2f, 10f),
            floatArrayOf(12f, 4f, 14f, 6f),
            floatArrayOf(3f, 11f, 1f, 9f),
            floatArrayOf(15f, 7f, 13f, 5f)
        )
        var idx = 0
        for (y in 0 until TARGET_H) {
            val row = b4[y and 3]
            for (x in 0 until TARGET_W) {
                this[idx++] = (row[x and 3] / 16.0f) - 0.5f
            }
        }
    }

    private val PROFILES = arrayOf(
        1 to 0,  1 to 2,  1 to 4,  1 to 6,
        2 to 7,  2 to 8,  2 to 10, 2 to 12,
        3 to 13, 3 to 14, 3 to 15, 3 to 16,
        4 to 17, 4 to 18, 4 to 20, 5 to 22,
        5 to 24, 5 to 26
    )

    data class LadderStep(
        val fps: Int,
        val comp: Int,
        val pain: Double,
        val roundedPain: Double
    ) : Comparable<LadderStep> {
        override fun compareTo(other: LadderStep): Int {
            val c1 = roundedPain.compareTo(other.roundedPain)
            if (c1 != 0) return c1
            val c2 = other.fps.compareTo(fps)
            if (c2 != 0) return c2
            return comp.compareTo(other.comp)
        }
    }

    data class EncodedChunk(
        val deltaBytes: ByteArray,
        val bbox: IntArray?
    )

    data class CompiledExndResult(
        val file: File,
        val fps: Int,
        val profileTag: String,
        val totalFrames: Int,
        val sizeBytes: Int,
        val previewBitmap: Bitmap
    )

    private fun getTacticalPainV31(f: Int, c: Int, idealFps: Int): Double {
        val fLoss = (idealFps - f).toDouble() / max(1, idealFps - 5).toDouble()
        val cLoss = c.toDouble() / 17.0
        val cPen = if (c > 5) (c - 5) * 3.0 else 0.0
        val fPen = if (f < 12) (12 - f) * 10.0 else 0.0
        val basePain = (fLoss * 2.0) + (cLoss * 1.5)
        val meanLoss = (fLoss + cLoss) / 2.0
        val variance = ((fLoss - meanLoss) * (fLoss - meanLoss) + (cLoss - meanLoss) * (cLoss - meanLoss)) / 2.0
        val balancePain = variance * 2.0
        return basePain + balancePain + cPen + fPen
    }

    private fun buildV31Ladder(idealFps: Int): List<LadderStep> {
        val ladder = ArrayList<LadderStep>(idealFps * 18)
        for (f in idealFps downTo 1) {
            for (c in 0 until 18) {
                val pain = getTacticalPainV31(f, c, idealFps)
                val rounded = (pain * 20.0).roundToInt() / 20.0
                ladder.add(LadderStep(f, c, pain, rounded))
            }
        }
        ladder.sort()
        return ladder
    }

    private fun rgb888ToRgb565Swapped(
        rawRgb: ByteArray,
        compLevel: Int,
        out565: ShortArray
    ) {
        val (c, _) = PROFILES[compLevel]
        val crushRb = c
        val crushG = max(1, c / 2)

        var rIdx = 0
        for (i in 0 until NUM_PIXELS) {
            var r = rawRgb[rIdx++].toInt() and 0xFF
            var g = rawRgb[rIdx++].toInt() and 0xFF
            var b = rawRgb[rIdx++].toInt() and 0xFF

            if (c > 1) {
                val dither = (BAYER_LUT[i] * c).roundToInt()
                r = ((r + dither).coerceIn(0, 255) / crushRb) * crushRb
                g = ((g + dither).coerceIn(0, 255) / crushG) * crushG
                b = ((b + dither).coerceIn(0, 255) / crushRb) * crushRb
            }

            val r5 = (r ushr 3) and 0x1F
            val g6 = (g ushr 2) and 0x3F
            val b5 = (b ushr 3) and 0x1F
            val p = (r5 shl 11) or (g6 shl 5) or b5
            val swapped = ((p ushr 8) and 0x00FF) or ((p shl 8) and 0xFF00)
            out565[i] = swapped.toShort()
        }
    }

    private fun encodeDeltaFast(
        currFlat: ShortArray,
        diffIndices: IntArray,
        diffCount: Int,
        vramFlat: ShortArray,
        workBuf: ByteBuffer
    ): ByteArray {
        workBuf.clear()
        var lastIdx = -1
        var currentSkip = 0
        var runStartIdx = -1
        var runLen = 0
        val runPixels = ShortArray(65535)

        fun flushRun() {
            if (runLen > 0) {
                workBuf.putShort(currentSkip.toShort())
                workBuf.putShort(runLen.toShort())
                for (k in 0 until runLen) {
                    workBuf.putShort(runPixels[k])
                }
                runLen = 0
            }
        }

        for (d in 0 until diffCount) {
            val idx = diffIndices[d]
            val skip = idx - lastIdx - 1
            if (skip > 0) {
                if (runLen > 0 && skip <= 2 && (runLen + skip + 1) <= 65534) {
                    for (gapIdx in (lastIdx + 1) until idx) {
                        runPixels[runLen++] = vramFlat[gapIdx]
                    }
                    runPixels[runLen++] = currFlat[idx]
                    lastIdx = idx
                    continue
                }

                flushRun()

                currentSkip = skip
                while (currentSkip > 65534) {
                    workBuf.putShort(65534.toShort())
                    workBuf.putShort(0.toShort())
                    currentSkip -= 65534
                }
            }
            if (runLen == 0 && runStartIdx == -1) {
                runStartIdx = idx
            }
            runPixels[runLen++] = currFlat[idx]
            lastIdx = idx
            if (runLen >= 65534) {
                flushRun()
                currentSkip = 0
            }
        }

        flushRun()
        workBuf.putShort(0xFFFF.toShort())
        workBuf.putShort(0.toShort())

        val out = ByteArray(workBuf.position())
        workBuf.flip()
        workBuf.get(out)
        return out
    }

    private class EvalCandidate(
        val fps: Int,
        val comp: Int,
        val totalBytes: Int,
        val frame0: ShortArray,
        val chunks: List<EncodedChunk>
    )

    private fun evaluateConfigFastAbort(
        masterFrames: List<ByteArray>,
        idealFps: Int,
        targetFps: Int,
        compLevel: Int,
        keepPayload: Boolean,
        diffIndicesScratch: IntArray,
        deltaScratchBuf: ByteBuffer
    ): EvalCandidate? {
        val step = idealFps.toDouble() / targetFps.toDouble()
        val count = (masterFrames.size * targetFps / idealFps).coerceAtLeast(1)
        val sampled = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val idx = (i * step).toInt()
            if (idx < masterFrames.size) sampled.add(masterFrames[idx])
        }
        if (sampled.isEmpty()) return null

        val (_, baseNt) = PROFILES[compLevel]
        val frame0_565 = ShortArray(NUM_PIXELS)
        rgb888ToRgb565Swapped(sampled[0], compLevel, frame0_565)

        val vram565 = frame0_565.clone()
        val frozenClean = sampled[0].clone()
        val curr565 = ShortArray(NUM_PIXELS)

        var totalBytes = 16 + (NUM_PIXELS * 2)
        if (totalBytes > MAX_TARGET_BYTES) return null

        val chunksList = if (keepPayload) ArrayList<EncodedChunk>(sampled.size) else null

        for (fIdx in 1 until sampled.size) {
            val rawCurr = sampled[fIdx]
            rgb888ToRgb565Swapped(rawCurr, compLevel, curr565)

            var activeNt = baseNt
            var encodedDelta: ByteArray? = null
            var encodedBbox: IntArray? = null
            var diffCount = 0

            for (attempt in 0..3) {
                diffCount = 0
                var minX = TARGET_W
                var maxX = 0
                var minY = TARGET_H
                var maxY = 0

                var p3 = 0
                for (px in 0 until NUM_PIXELS) {
                    val dr = abs((rawCurr[p3].toInt() and 0xFF) - (frozenClean[p3].toInt() and 0xFF))
                    val dg = abs((rawCurr[p3 + 1].toInt() and 0xFF) - (frozenClean[p3 + 1].toInt() and 0xFF))
                    val db = abs((rawCurr[p3 + 2].toInt() and 0xFF) - (frozenClean[p3 + 2].toInt() and 0xFF))
                    p3 += 3

                    if ((dr > activeNt || dg > activeNt || db > activeNt) && (curr565[px] != vram565[px])) {
                        diffIndicesScratch[diffCount++] = px
                        val y = px / TARGET_W
                        val x = px % TARGET_W
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }

                if (diffCount == 0) {
                    encodedDelta = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00)
                    encodedBbox = null
                    break
                }

                val candidateDelta = encodeDeltaFast(curr565, diffIndicesScratch, diffCount, vram565, deltaScratchBuf)
                if (candidateDelta.size + 8 <= MAX_FRAME_DECODER_BYTES) {
                    encodedDelta = candidateDelta
                    encodedBbox = intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
                    break
                } else {
                    activeNt += 10
                }
            }

            if (encodedDelta == null) return null

            if (encodedBbox == null) {
                totalBytes += 4 + 4
                if (totalBytes > MAX_TARGET_BYTES) return null
                chunksList?.add(EncodedChunk(encodedDelta, null))
            } else {
                for (d in 0 until diffCount) {
                    val px = diffIndicesScratch[d]
                    vram565[px] = curr565[px]
                    val b3 = px * 3
                    frozenClean[b3] = rawCurr[b3]
                    frozenClean[b3 + 1] = rawCurr[b3 + 1]
                    frozenClean[b3 + 2] = rawCurr[b3 + 2]
                }
                val frameLen = encodedDelta.size + 8
                totalBytes += 4 + frameLen
                if (totalBytes > MAX_TARGET_BYTES) return null
                chunksList?.add(EncodedChunk(encodedDelta, encodedBbox))
            }
        }

        return EvalCandidate(
            fps = targetFps,
            comp = compLevel,
            totalBytes = totalBytes,
            frame0 = frame0_565,
            chunks = chunksList ?: emptyList()
        )
    }

    private fun applySpatialDenoise205x251(rgb: ByteArray): ByteArray {
        val out = rgb.clone()
        val w = TARGET_W
        val h = TARGET_H
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val cIdx = (y * w + x) * 3
                val cr = rgb[cIdx].toInt() and 0xFF
                val cg = rgb[cIdx + 1].toInt() and 0xFF
                val cb = rgb[cIdx + 2].toInt() and 0xFF

                var sr = cr * 4
                var sg = cg * 4
                var sb = cb * 4
                var wSum = 4

                val neighbors = intArrayOf(
                    ((y - 1) * w + x) * 3,
                    ((y + 1) * w + x) * 3,
                    (y * w + (x - 1)) * 3,
                    (y * w + (x + 1)) * 3
                )
                for (nIdx in neighbors) {
                    val nr = rgb[nIdx].toInt() and 0xFF
                    val ng = rgb[nIdx + 1].toInt() and 0xFF
                    val nb = rgb[nIdx + 2].toInt() and 0xFF
                    if (abs(nr - cr) + abs(ng - cg) + abs(nb - cb) < 38) {
                        sr += nr
                        sg += ng
                        sb += nb
                        wSum += 1
                    }
                }
                out[cIdx] = (sr / wSum).toByte()
                out[cIdx + 1] = (sg / wSum).toByte()
                out[cIdx + 2] = (sb / wSum).toByte()
            }
        }
        return out
    }

    private fun projectBitmapToFsrRgb(
        bmp: Bitmap,
        cropMode: Int,
        normCrop: RectF
    ): ByteArray {
        val srcW = bmp.width
        val srcH = bmp.height
        val targetBmp = Bitmap.createBitmap(TARGET_W, TARGET_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(targetBmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        if (cropMode == 1) {
            val left = (normCrop.left * srcW).toInt().coerceIn(0, srcW - 2)
            val top = (normCrop.top * srcH).toInt().coerceIn(0, srcH - 2)
            val right = (normCrop.right * srcW).toInt().coerceIn(left + 1, srcW)
            val bottom = (normCrop.bottom * srcH).toInt().coerceIn(top + 1, srcH)
            val srcRect = android.graphics.Rect(left, top, right, bottom)
            val dstRect = android.graphics.Rect(0, 0, TARGET_W, TARGET_H)
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        } else {
            val scale = min(TARGET_W.toFloat() / srcW.toFloat(), TARGET_H.toFloat() / srcH.toFloat())
            val nw = (srcW * scale).toInt().coerceAtLeast(1)
            val nh = (srcH * scale).toInt().coerceAtLeast(1)
            val sx = (TARGET_W - nw) / 2
            val sy = (TARGET_H - nh) / 2
            val dstRect = android.graphics.Rect(sx, sy, sx + nw, sy + nh)
            canvas.drawBitmap(bmp, null, dstRect, paint)
        }

        val pixels = IntArray(NUM_PIXELS)
        targetBmp.getPixels(pixels, 0, TARGET_W, 0, 0, TARGET_W, TARGET_H)
        targetBmp.recycle()

        val rawRgb = ByteArray(NUM_PIXELS * 3)
        var outIdx = 0
        for (i in 0 until NUM_PIXELS) {
            val p = pixels[i]
            rawRgb[outIdx++] = ((p ushr 16) and 0xFF).toByte()
            rawRgb[outIdx++] = ((p ushr 8) and 0xFF).toByte()
            rawRgb[outIdx++] = (p and 0xFF).toByte()
        }
        return applySpatialDenoise205x251(rawRgb)
    }

    fun extractMasterFramesHardware(
        context: Context,
        uri: Uri,
        idealFps: Int,
        cropMode: Int,
        normCrop: RectF,
        onProgress: (String, Int) -> Unit
    ): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        val mime = context.contentResolver.getType(uri) ?: ""

        if (mime.contains("gif", ignoreCase = true)) {
            onProgress("Decodificando GIF animado...", 10)
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bytes = ins.readBytes()
                val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                if (movie != null && movie.width() > 0 && movie.height() > 0) {
                    val dur = movie.duration().coerceAtLeast(1000)
                    val stepMs = (1000 / idealFps).coerceAtLeast(33)
                    val totalF = (dur / stepMs).coerceIn(1, 450)
                    val tempBmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                    val tempCanvas = Canvas(tempBmp)
                    for (f in 0 until totalF) {
                        tempCanvas.drawColor(Color.BLACK)
                        movie.setTime((f * stepMs) % dur)
                        movie.draw(tempCanvas, 0f, 0f)
                        frames.add(projectBitmapToFsrRgb(tempBmp, cropMode, normCrop))
                        if (f % 5 == 0) {
                            onProgress("Extrayendo GIF: cuadro ${f + 1}/$totalF", (f * 100) / totalF)
                        }
                    }
                    tempBmp.recycle()
                    return frames
                }
            }
        }

        if (mime.startsWith("image/") && !mime.contains("gif", ignoreCase = true)) {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bmp = BitmapFactory.decodeStream(ins)
                if (bmp != null) {
                    val projected = projectBitmapToFsrRgb(bmp, cropMode, normCrop)
                    bmp.recycle()
                    frames.add(projected)
                    return frames
                }
            }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }

            var videoTrack = -1
            var format: MediaFormat? = null
            var mimeType = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    mimeType = m
                    break
                }
            }

            if (videoTrack >= 0 && format != null) {
                extractor.selectTrack(videoTrack)
                val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else 0
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else 10_000_000L

                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )

                codec = MediaCodec.createDecoderByType(mimeType)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                val frameIntervalUs = (1_000_000L / idealFps.coerceAtLeast(1))
                var nextTargetTimeUs = 0L
                var inputEos = false
                var outputEos = false
                val maxFramesSafety = 550

                while (!outputEos && frames.size < maxFramesSafety) {
                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(8000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    val pts = extractor.sampleTime
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 8000)
                    if (outIdx >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                        val pts = bufferInfo.presentationTimeUs
                        val shouldSample = (bufferInfo.size > 0) && (pts >= nextTargetTimeUs)

                        if (shouldSample) {
                            nextTargetTimeUs = pts + frameIntervalUs
                            val img = codec.getOutputImage(outIdx)
                            if (img != null) {
                                val rgb205 = sampleYuvImageToFsr205x251(img, rotation, cropMode, normCrop)
                                img.close()
                                frames.add(applySpatialDenoise205x251(rgb205))
                                if (frames.size % 6 == 0) {
                                    val pct = if (durationUs > 0) ((pts * 100L) / durationUs).toInt().coerceIn(0, 99) else 50
                                    onProgress("DSP Hardware: ${frames.size} cuadros extraídos...", pct)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }

        if (frames.isEmpty()) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durMs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L).coerceAtMost(18000L)
                val stepUs = (1_000_000L / idealFps)
                val totalF = ((durMs * 1000L) / stepUs).toInt().coerceIn(1, 300)
                for (i in 0 until totalF) {
                    val bmp = retriever.getFrameAtTime(i * stepUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        frames.add(projectBitmapToFsrRgb(bmp, cropMode, normCrop))
                        bmp.recycle()
                    }
                    if (i % 5 == 0) onProgress("Extrayendo fotogramas (${i + 1}/$totalF)...", (i * 100) / totalF)
                }
            } catch (_: Throwable) {
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }

        return frames
    }

    private fun sampleYuvImageToFsr205x251(
        image: android.media.Image,
        rotationDegrees: Int,
        cropMode: Int,
        normCrop: RectF
    ): ByteArray {
        val rawW = image.width
        val rawH = image.height
        val rot = ((rotationDegrees % 360) + 360) % 360
        val dispW = if (rot == 90 || rot == 270) rawH else rawW
        val dispH = if (rot == 90 || rot == 270) rawW else rawH

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val outRgb = ByteArray(NUM_PIXELS * 3)

        val cLeft: Float
        val cTop: Float
        val cWidth: Float
        val cHeight: Float

        var dstX0 = 0
        var dstY0 = 0
        var dstW = TARGET_W
        var dstH = TARGET_H

        if (cropMode == 1) {
            cLeft = normCrop.left * dispW
            cTop = normCrop.top * dispH
            cWidth = max(1f, (normCrop.right - normCrop.left) * dispW)
            cHeight = max(1f, (normCrop.bottom - normCrop.top) * dispH)
        } else {
            cLeft = 0f
            cTop = 0f
            cWidth = dispW.toFloat()
            cHeight = dispH.toFloat()
            val scale = min(TARGET_W.toFloat() / cWidth, TARGET_H.toFloat() / cHeight)
            dstW = (cWidth * scale).toInt().coerceIn(1, TARGET_W)
            dstH = (cHeight * scale).toInt().coerceIn(1, TARGET_H)
            dstX0 = (TARGET_W - dstW) / 2
            dstY0 = (TARGET_H - dstH) / 2
        }

        val yLim = yBuf.limit()
        val uLim = uBuf.limit()
        val vLim = vBuf.limit()

        for (ty in 0 until dstH) {
            val dy = dstY0 + ty
            val vNorm = (ty + 0.5f) / dstH.toFloat()
            val syDisp = (cTop + vNorm * cHeight).toInt().coerceIn(0, dispH - 1)

            for (tx in 0 until dstW) {
                val dx = dstX0 + tx
                val uNorm = (tx + 0.5f) / dstW.toFloat()
                val sxDisp = (cLeft + uNorm * cWidth).toInt().coerceIn(0, dispW - 1)

                val rx: Int
                val ry: Int
                when (rot) {
                    90 -> {
                        rx = syDisp
                        ry = rawH - 1 - sxDisp
                    }
                    180 -> {
                        rx = rawW - 1 - sxDisp
                        ry = rawH - 1 - syDisp
                    }
                    270 -> {
                        rx = rawW - 1 - syDisp
                        ry = sxDisp
                    }
                    else -> {
                        rx = sxDisp
                        ry = syDisp
                    }
                }

                val yPos = ry * yRowStride + rx * yPixStride
                val uvX = rx ushr 1
                val uvY = ry ushr 1
                val uPos = uvY * uRowStride + uvX * uPixStride
                val vPos = uvY * vRowStride + uvX * vPixStride

                val yVal = if (yPos in 0 until yLim) (yBuf.get(yPos).toInt() and 0xFF) else 0
                val uVal = if (uPos in 0 until uLim) (uBuf.get(uPos).toInt() and 0xFF) - 128 else 0
                val vVal = if (vPos in 0 until vLim) (vBuf.get(vPos).toInt() and 0xFF) - 128 else 0

                val r = (yVal + ((1436 * vVal) shr 10)).coerceIn(0, 255)
                val g = (yVal - ((352 * uVal + 731 * vVal) shr 10)).coerceIn(0, 255)
                val b = (yVal + ((1815 * uVal) shr 10)).coerceIn(0, 255)

                val outIdx = (dy * TARGET_W + dx) * 3
                outRgb[outIdx] = r.toByte()
                outRgb[outIdx + 1] = g.toByte()
                outRgb[outIdx + 2] = b.toByte()
            }
        }

        return outRgb
    }

    fun compileMasterFramesToExnd(
        context: Context,
        wallpaperName: String,
        masterFrames: List<ByteArray>,
        idealFps: Int,
        onProgress: (String, Int) -> Unit
    ): CompiledExndResult? {
        if (masterFrames.isEmpty()) return null

        val ladder = buildV31Ladder(idealFps)
        val stride = max(5, ladder.size / 100)
        var fallbackIdx = -1

        val diffIndicesScratch = IntArray(NUM_PIXELS)
        val deltaScratchBuf = ByteBuffer.allocate(NUM_PIXELS * 4 + 1024).order(ByteOrder.LITTLE_ENDIAN)

        for (i in ladder.indices step stride) {
            val step = ladder[i]
            val pct = 10 + ((i * 55) / ladder.size.coerceAtLeast(1))
            onProgress("Evaluando Perfil $i/${ladder.size} | ${step.fps} FPS | CL${step.comp}...", pct)

            val cand = evaluateConfigFastAbort(
                masterFrames = masterFrames,
                idealFps = idealFps,
                targetFps = step.fps,
                compLevel = step.comp,
                keepPayload = false,
                diffIndicesScratch = diffIndicesScratch,
                deltaScratchBuf = deltaScratchBuf
            )
            if (cand != null && cand.totalBytes <= MAX_TARGET_BYTES) {
                fallbackIdx = max(0, i - stride)
                break
            }
        }

        if (fallbackIdx < 0) return null

        val endIdx = min(fallbackIdx + stride + 1, ladder.size)
        var bestCandidate: EvalCandidate? = null

        for (i in fallbackIdx until endIdx) {
            val step = ladder[i]
            val pct = 68 + (((i - fallbackIdx) * 25) / max(1, endIdx - fallbackIdx))
            onProgress("Barrido final | ${step.fps} FPS | CL${step.comp}...", pct)

            val cand = evaluateConfigFastAbort(
                masterFrames = masterFrames,
                idealFps = idealFps,
                targetFps = step.fps,
                compLevel = step.comp,
                keepPayload = true,
                diffIndicesScratch = diffIndicesScratch,
                deltaScratchBuf = deltaScratchBuf
            )
            if (cand != null && cand.totalBytes <= MAX_TARGET_BYTES) {
                if (bestCandidate == null || cand.totalBytes > bestCandidate.totalBytes) {
                    bestCandidate = cand
                }
            }
        }

        val winner = bestCandidate ?: return null
        onProgress("Empaquetando binario .EXND (${String.format(Locale.US, "%.2f", winner.totalBytes / 1048576.0)} MB)...", 96)

        val outDir = File(context.getExternalFilesDir(null), "EXOSYS_WALLPAPERS")
        if (!outDir.exists()) outDir.mkdirs()
        val cleanName = wallpaperName.trim().ifBlank { "FONDO_EXOSYS" }
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "")
            .trim()
        val outFile = File(outDir, "${cleanName.replace(" ", "_")}.exnd")

        FileOutputStream(outFile).use { fos ->
            val hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            hdr.put("EXND".toByteArray(Charsets.US_ASCII))
            hdr.putShort(TARGET_W.toShort())
            hdr.putShort(TARGET_H.toShort())
            hdr.putInt(1 + winner.chunks.size)
            hdr.put(winner.fps.toByte())
            hdr.put(0.toByte())
            hdr.put(0.toByte())
            hdr.put(0.toByte())
            fos.write(hdr.array())

            val f0Bytes = ByteBuffer.allocate(NUM_PIXELS * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in winner.frame0) f0Bytes.putShort(s)
            fos.write(f0Bytes.array())

            val lenBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in winner.chunks) {
                lenBuf.clear()
                if (chunk.bbox != null) {
                    lenBuf.putInt(chunk.deltaBytes.size + 8)
                    lenBuf.putShort(chunk.bbox[0].toShort())
                    lenBuf.putShort(chunk.bbox[1].toShort())
                    lenBuf.putShort(chunk.bbox[2].toShort())
                    lenBuf.putShort(chunk.bbox[3].toShort())
                    fos.write(lenBuf.array(), 0, 12)
                    fos.write(chunk.deltaBytes)
                } else {
                    lenBuf.putInt(chunk.deltaBytes.size)
                    fos.write(lenBuf.array(), 0, 4)
                    fos.write(chunk.deltaBytes)
                }
            }
        }

        val previewBmp = decodeExndHeaderPreview(outFile)
            ?: Bitmap.createBitmap(TARGET_W, TARGET_H, Bitmap.Config.ARGB_8888)

        return CompiledExndResult(
            file = outFile,
            fps = winner.fps,
            profileTag = "CL${winner.comp}",
            totalFrames = 1 + winner.chunks.size,
            sizeBytes = outFile.length().toInt(),
            previewBitmap = previewBmp
        )
    }

    fun decodeExndHeaderPreview(exndFile: File): Bitmap? {
        if (!exndFile.exists() || exndFile.length() < 16 + NUM_PIXELS * 2) return null
        return try {
            FileInputStream(exndFile).use { fis ->
                val hdr = ByteArray(16)
                if (fis.read(hdr) != 16) return null
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val w = bb.getShort(4).toInt() and 0xFFFF
                val h = bb.getShort(6).toInt() and 0xFFFF
                if (w <= 0 || h <= 0 || w * h > 300000) return null

                val raw0 = ByteArray(w * h * 2)
                if (fis.read(raw0) != raw0.size) return null

                val argb = IntArray(w * h)
                var bIdx = 0
                for (i in 0 until w * h) {
                    val hi = raw0[bIdx++].toInt() and 0xFF
                    val lo = raw0[bIdx++].toInt() and 0xFF
                    val rgb565 = (hi shl 8) or lo
                    val r8 = ((rgb565 ushr 11) and 0x1F) shl 3
                    val g8 = ((rgb565 ushr 5) and 0x3F) shl 2
                    val b8 = (rgb565 and 0x1F) shl 3
                    argb[i] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                }
                Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
            }
        } catch (_: Throwable) {
            null
        }
    }
}

// =============================================================================
// 1B. PRE-PROCESADOR DE PANTALLA DE CARGA DEL MAPA (256x256, GARANTIZADO <= 38 KB)
// =============================================================================
object MapPlaceholderCompiler {

    const val TILE_SIDE = 256
    const val TILE_PIXELS = TILE_SIDE * TILE_SIDE // 65,536 píxeles
    const val RAW_BIN_BYTES = TILE_PIXELS * 2     // 131,072 bytes exactos en PSRAM
    const val MAX_QOI_SAFE_BYTES = 38912          // Tope duro: <= 38.0 KB (entra en QOI_CHUNK_SIZE=40KB y COMPRESSED_BUF=64KB)

    private val BAYER_256_LUT = FloatArray(TILE_PIXELS).apply {
        val b4 = arrayOf(
            floatArrayOf(0f, 8f, 2f, 10f),
            floatArrayOf(12f, 4f, 14f, 6f),
            floatArrayOf(3f, 11f, 1f, 9f),
            floatArrayOf(15f, 7f, 13f, 5f)
        )
        var idx = 0
        for (y in 0 until TILE_SIDE) {
            val row = b4[y and 3]
            for (x in 0 until TILE_SIDE) {
                this[idx++] = (row[x and 3] / 16.0f) - 0.5f
            }
        }
    }

    private data class CompressionStage(
        val levelTag: Int,
        val ditherAmp: Float,
        val crushRb: Int,
        val crushG: Int,
        val runSnapTol: Int, // Fusiona micro-ruido JPEG horizontal para alargar cadenas RLE
        val blockW: Int,
        val blockH: Int
    )

    // Escalera de 12 etapas: preserva 256x256 nítido en CL1..CL8 y aplica bloques 2x1 / 2x2 en casos extremos
    private val STAGES = arrayOf(
        CompressionStage(1,  2.0f,  1,  1,  0, 1, 1),
        CompressionStage(2,  0.0f,  1,  1,  3, 1, 1),
        CompressionStage(3,  0.0f,  2,  1,  6, 1, 1),
        CompressionStage(4,  0.0f,  3,  2, 10, 1, 1),
        CompressionStage(5,  0.0f,  4,  2, 14, 1, 1),
        CompressionStage(6,  0.0f,  6,  3, 18, 1, 1),
        CompressionStage(7,  0.0f,  8,  4, 24, 1, 1),
        CompressionStage(8,  0.0f, 10,  5, 30, 1, 1),
        CompressionStage(9,  0.0f,  4,  2, 12, 2, 1),
        CompressionStage(10, 0.0f,  8,  4, 20, 2, 1),
        CompressionStage(11, 0.0f,  6,  3, 14, 2, 2),
        CompressionStage(12, 0.0f, 12,  6, 24, 2, 2) // Garantía matemática <= 32 KB incluso en ruido puro
    )

    data class RegulatedTileData(
        val previewBitmap: Bitmap,
        val pixels565: ShortArray,
        val qoiBytes: ByteArray,
        val crushLevel: Int
    )

    data class CompiledPlaceholderResult(
        val qoiFile: File,
        val binFile: File,
        val qoiSizeBytes: Int,
        val binSizeBytes: Int,
        val crushLevel: Int,
        val previewBitmap: Bitmap
    )

    fun decodeStaticImageOnly(context: Context, uri: Uri): Result<Bitmap> {
        val mime = (context.contentResolver.getType(uri) ?: "").lowercase(Locale.US)
        if (mime.startsWith("video/") || mime.contains("gif")) {
            return Result.failure(
                Exception("El mosaico de carga del mapa debe ser una IMAGEN ESTÁTICA (JPG/PNG/WEBP) para no atrasar CartoEngine.")
            )
        }
        return try {
            var bmp: Bitmap? = null
            context.contentResolver.openInputStream(uri)?.use { ins ->
                bmp = BitmapFactory.decodeStream(ins)
            }
            if (bmp != null && bmp!!.width > 0 && bmp!!.height > 0) {
                Result.success(bmp!!)
            } else {
                Result.failure(Exception("No se pudo abrir la imagen seleccionada."))
            }
        } catch (t: Throwable) {
            Result.failure(Exception("Error leyendo imagen: ${t.localizedMessage}"))
        }
    }

    fun extractPreviewBitmapAnyMedia(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bmp = BitmapFactory.decodeStream(ins)
                if (bmp != null) return bmp
            }
            val ret = MediaMetadataRetriever()
            try {
                ret.setDataSource(context, uri)
                ret.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                try { ret.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            null
        }
    }

    // Genera un archivo .QOI con la retícula táctica original de CartoEngine.cpp (render_fallback_tactical_placeholder)
    fun buildDefaultTacticalGridQoiFile(context: Context): File {
        val pixels565 = ShortArray(TILE_PIXELS)
        val cBg     = 0x0842
        val cSub    = 0x10A3
        val cGrid   = 0x1925
        val cBorder = 0x21A7
        val cCross  = 0x03EF

        for (y in 0 until TILE_SIDE) {
            val rowOff = y * TILE_SIDE
            val isBorderY = (y == 0 || y == 255)
            val isGridY   = ((y and 63) == 0)
            val isSubY    = ((y and 31) == 0)
            for (x in 0 until TILE_SIDE) {
                var col = cBg
                if (isBorderY || x == 0 || x == 255) {
                    col = cBorder
                } else if (isGridY || (x and 63) == 0) {
                    col = cGrid
                } else if (isSubY && (x and 3) == 0) {
                    col = cSub
                } else if ((x and 31) == 0 && (y and 3) == 0) {
                    col = cSub
                }
                if ((y == 128 && x in 120..136) || (x == 128 && y in 120..136)) {
                    col = cCross
                }
                pixels565[rowOff + x] = col.toShort()
            }
        }

        val qoiBytes = encodeRgb565ToQoi565Bytes(pixels565)
        val vaultDir = File(context.getExternalFilesDir(null), "EXOSYS_PLACEHOLDERS")
        if (!vaultDir.exists()) vaultDir.mkdirs()
        val defaultFile = File(vaultDir, "DEFAULT_RETICULA_TACTICA.qoi")
        defaultFile.writeBytes(qoiBytes)
        return defaultFile
    }

    // Proyecta a 256x256 y regula automáticamente hasta garantizar que el QOI pese <= 38.0 KB
    fun preProcessAndRegulate256(
        srcBmp: Bitmap,
        cropMode: Int,
        normCrop: RectF
    ): RegulatedTileData {
        val srcW = srcBmp.width
        val srcH = srcBmp.height
        val workBmp = Bitmap.createBitmap(TILE_SIDE, TILE_SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(workBmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        if (cropMode == 1) {
            val left = (normCrop.left * srcW).toInt().coerceIn(0, max(0, srcW - 2))
            val top = (normCrop.top * srcH).toInt().coerceIn(0, max(0, srcH - 2))
            val right = (normCrop.right * srcW).toInt().coerceIn(left + 1, srcW)
            val bottom = (normCrop.bottom * srcH).toInt().coerceIn(top + 1, srcH)
            canvas.drawBitmap(
                srcBmp,
                android.graphics.Rect(left, top, right, bottom),
                android.graphics.Rect(0, 0, TILE_SIDE, TILE_SIDE),
                paint
            )
        } else {
            val scale = min(TILE_SIDE.toFloat() / srcW.toFloat(), TILE_SIDE.toFloat() / srcH.toFloat())
            val nw = (srcW * scale).toInt().coerceAtLeast(1)
            val nh = (srcH * scale).toInt().coerceAtLeast(1)
            val sx = (TILE_SIDE - nw) / 2
            val sy = (TILE_SIDE - nh) / 2
            canvas.drawBitmap(
                srcBmp,
                null,
                android.graphics.Rect(sx, sy, sx + nw, sy + nh),
                paint
            )
        }

        val argb = IntArray(TILE_PIXELS)
        workBmp.getPixels(argb, 0, TILE_SIDE, 0, 0, TILE_SIDE, TILE_SIDE)

        val out565 = ShortArray(TILE_PIXELS)
        val previewArgb = IntArray(TILE_PIXELS)
        var bestQoi = ByteArray(0)
        var chosenLevel = 1

        for (stage in STAGES) {
            var lastR = -999
            var lastG = -999
            var lastB = -999
            var last565: Short = 0
            var lastPreview = 0

            for (y in 0 until TILE_SIDE) {
                val sy = (y / stage.blockH) * stage.blockH
                val rowOff = y * TILE_SIDE
                val srcRowOff = sy * TILE_SIDE

                for (x in 0 until TILE_SIDE) {
                    val sx = (x / stage.blockW) * stage.blockW
                    val idx = rowOff + x
                    val px = argb[srcRowOff + sx]

                    var r = (px ushr 16) and 0xFF
                    var g = (px ushr 8) and 0xFF
                    var b = px and 0xFF

                    if (stage.ditherAmp > 0f) {
                        val d = (BAYER_256_LUT[idx] * stage.ditherAmp).roundToInt()
                        r = (r + d).coerceIn(0, 255)
                        g = (g + (d / 2)).coerceIn(0, 255)
                        b = (b + d).coerceIn(0, 255)
                    }

                    if (stage.crushRb > 1) {
                        r = (r / stage.crushRb) * stage.crushRb
                        b = (b / stage.crushRb) * stage.crushRb
                    }
                    if (stage.crushG > 1) {
                        g = (g / stage.crushG) * stage.crushG
                    }

                    // Si la diferencia con el píxel anterior es solo micro-ruido, reutilizar el color anterior (maximiza RLE)
                    if (stage.runSnapTol > 0 &&
                        abs(r - lastR) + abs(g - lastG) + abs(b - lastB) <= stage.runSnapTol
                    ) {
                        out565[idx] = last565
                        previewArgb[idx] = lastPreview
                    } else {
                        val r5 = (r ushr 3) and 0x1F
                        val g6 = (g ushr 2) and 0x3F
                        val b5 = (b ushr 3) and 0x1F
                        val rgb565 = ((r5 shl 11) or (g6 shl 5) or b5).toShort()

                        val qr8 = (r5 shl 3) or (r5 ushr 2)
                        val qg8 = (g6 shl 2) or (g6 ushr 4)
                        val qb8 = (b5 shl 3) or (b5 ushr 2)
                        val pCol = (0xFF shl 24) or (qr8 shl 16) or (qg8 shl 8) or qb8

                        out565[idx] = rgb565
                        previewArgb[idx] = pCol

                        lastR = r
                        lastG = g
                        lastB = b
                        last565 = rgb565
                        lastPreview = pCol
                    }
                }
            }

            val candQoi = encodeRgb565ToQoi565Bytes(out565)
            bestQoi = candQoi
            chosenLevel = stage.levelTag
            if (candQoi.size <= MAX_QOI_SAFE_BYTES) {
                break
            }
        }

        workBmp.setPixels(previewArgb, 0, TILE_SIDE, 0, 0, TILE_SIDE, TILE_SIDE)
        return RegulatedTileData(
            previewBitmap = workBmp,
            pixels565 = out565,
            qoiBytes = bestQoi,
            crushLevel = chosenLevel
        )
    }

    fun encodeRgb565ToQoi565Bytes(pixels565: ShortArray): ByteArray {
        val out = ByteArrayOutputStream(32768)
        val cache = IntArray(64)
        var prevPx = 0
        var run = 0

        for (i in 0 until TILE_PIXELS) {
            val px = pixels565[i].toInt() and 0xFFFF
            if (px == prevPx) {
                run++
                if (run == 64) {
                    out.write(0x3F)
                    run = 0
                }
            } else {
                if (run > 0) {
                    out.write(run - 1)
                    run = 0
                }
                val idx = (px xor (px ushr 3)) and 63
                if (cache[idx] == px) {
                    out.write(0x40 or idx)
                } else {
                    out.write(0x80)
                    out.write(px and 0xFF)
                    out.write((px ushr 8) and 0xFF)
                    cache[idx] = px
                }
                prevPx = px
            }
        }
        if (run > 0) {
            out.write(run - 1)
        }
        return out.toByteArray()
    }

    fun decodeSavedPlaceholderPreview(file: File): Bitmap? {
        if (!file.exists()) return null
        return try {
            val raw = file.readBytes()
            val argb = IntArray(TILE_PIXELS)
            if (file.name.endsWith(".bin", ignoreCase = true) && raw.size >= RAW_BIN_BYTES) {
                var bIdx = 0
                for (i in 0 until TILE_PIXELS) {
                    val lo = raw[bIdx++].toInt() and 0xFF
                    val hi = raw[bIdx++].toInt() and 0xFF
                    val px = (hi shl 8) or lo
                    val r8 = ((px ushr 11) and 0x1F) shl 3
                    val g8 = ((px ushr 5) and 0x3F) shl 2
                    val b8 = (px and 0x1F) shl 3
                    argb[i] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                }
                Bitmap.createBitmap(argb, TILE_SIDE, TILE_SIDE, Bitmap.Config.ARGB_8888)
            } else {
                val cache = IntArray(64)
                var inIdx = 0
                var outIdx = 0
                var prevPx = 0
                while (inIdx < raw.size && outIdx < TILE_PIXELS) {
                    val op = raw[inIdx++].toInt() and 0xFF
                    val tag = op and 0xC0
                    if (tag == 0x00) {
                        val run = (op and 0x3F) + 1
                        val r8 = ((prevPx ushr 11) and 0x1F) shl 3
                        val g8 = ((prevPx ushr 5) and 0x3F) shl 2
                        val b8 = (prevPx and 0x1F) shl 3
                        val col = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                        for (k in 0 until run) {
                            if (outIdx < TILE_PIXELS) argb[outIdx++] = col
                        }
                    } else if (tag == 0x40) {
                        val px = cache[op and 0x3F]
                        prevPx = px
                        val r8 = ((px ushr 11) and 0x1F) shl 3
                        val g8 = ((px ushr 5) and 0x3F) shl 2
                        val b8 = (px and 0x1F) shl 3
                        argb[outIdx++] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                    } else if (op == 0x80) {
                        if (inIdx + 1 >= raw.size) break
                        val px = (raw[inIdx].toInt() and 0xFF) or ((raw[inIdx + 1].toInt() and 0xFF) shl 8)
                        inIdx += 2
                        cache[(px xor (px ushr 3)) and 63] = px
                        prevPx = px
                        val r8 = ((px ushr 11) and 0x1F) shl 3
                        val g8 = ((px ushr 5) and 0x3F) shl 2
                        val b8 = (px and 0x1F) shl 3
                        argb[outIdx++] = (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                    }
                }
                Bitmap.createBitmap(argb, TILE_SIDE, TILE_SIDE, Bitmap.Config.ARGB_8888)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // Si había un archivo .QOI antiguo (> 38 KB) en la bóveda del teléfono, lo re-regula automáticamente antes de enviarlo
    fun ensureSafeQoiFile(context: Context, qoiFile: File): File {
        if (!qoiFile.exists() || qoiFile.length() <= MAX_QOI_SAFE_BYTES) return qoiFile
        val decodedBmp = decodeSavedPlaceholderPreview(qoiFile) ?: return qoiFile
        val reg = preProcessAndRegulate256(decodedBmp, 2, RectF(0f, 0f, 1f, 1f))
        decodedBmp.recycle()
        qoiFile.writeBytes(reg.qoiBytes)
        return qoiFile
    }

    fun compileAndSavePlaceholder(
        context: Context,
        customName: String,
        srcBmp: Bitmap,
        cropMode: Int,
        normCrop: RectF
    ): CompiledPlaceholderResult {
        val reg = preProcessAndRegulate256(srcBmp, cropMode, normCrop)

        val binBuf = ByteBuffer.allocate(RAW_BIN_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (s in reg.pixels565) binBuf.putShort(s)
        val binBytes = binBuf.array()

        val vaultDir = File(context.getExternalFilesDir(null), "EXOSYS_PLACEHOLDERS")
        if (!vaultDir.exists()) vaultDir.mkdirs()

        val cleanName = customName.trim().ifBlank { "PLACEHOLDER_MAPA" }
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "")
            .ifBlank { "PLACEHOLDER_MAPA" }

        val qoiFile = File(vaultDir, "$cleanName.qoi")
        val binFile = File(vaultDir, "$cleanName.bin")
        qoiFile.writeBytes(reg.qoiBytes)
        binFile.writeBytes(binBytes)

        try {
            val dlDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "EXOSYS_LINK/UI"
            )
            if (!dlDir.exists()) dlDir.mkdirs()
            File(dlDir, "placeholder.qoi").writeBytes(reg.qoiBytes)
            File(dlDir, "placeholder.bin").writeBytes(binBytes)
        } catch (_: Throwable) {}

        return CompiledPlaceholderResult(
            qoiFile = qoiFile,
            binFile = binFile,
            qoiSizeBytes = reg.qoiBytes.size,
            binSizeBytes = binBytes.size,
            crushLevel = reg.crushLevel,
            previewBitmap = reg.previewBitmap
        )
    }
}

// =============================================================================
// 2. PUENTE TCP PUERTO 8892 (FONDOS .EXND + PLACEHOLDER DE MAPA EN SD)
// =============================================================================
object ExndWatchTcpBridge {

    const val WATCH_EXND_PORT = 8892
    const val SLOT_MAP_PLACEHOLDER_QOI = 250 // Slot reservado para /UI/placeholder.qoi
    const val SLOT_MAP_PLACEHOLDER_BIN = 251 // Slot reservado para /UI/placeholder.bin

    data class RemoteWatchWallpaper(
        val index: Int,
        val customName: String,
        val width: Int,
        val height: Int,
        val frames: Int,
        val fps: Int,
        val sizeMb: Double
    )

    fun uploadExndToWatchSd(
        watchIp: String,
        exndFile: File,
        customName: String,
        targetSlot: Int = 0,
        activateNow: Boolean = true,
        onProgress: (Int) -> Unit,
        onResult: (Result<String>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                val cleanIp = watchIp.trim().removePrefix("http://").substringBefore(":")
                if (cleanIp.isEmpty() || cleanIp == "0.0.0.0") {
                    return@thread onResult(Result.failure(Exception("IP del reloj vacía. Enlaza el Hotspot o escribe la IP del reloj arriba.")))
                }
                if (!exndFile.exists()) {
                    return@thread onResult(Result.failure(Exception("Archivo no encontrado.")))
                }

                val totalBytes = exndFile.length().toInt()
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(cleanIp, WATCH_EXND_PORT), 5000)
                    socket.soTimeout = 15000

                    val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    hdr.put("EXUP".toByteArray(Charsets.US_ASCII))
                    hdr.put((targetSlot and 0xFF).toByte())
                    hdr.put((if (activateNow) 1 else 0).toByte())
                    hdr.putShort(0.toShort())
                    hdr.putInt(totalBytes)

                    val nameBytes = ByteArray(32)
                    val rawName = customName.trim().ifBlank { exndFile.nameWithoutExtension }
                        .toByteArray(Charsets.UTF_8)
                    System.arraycopy(rawName, 0, nameBytes, 0, min(31, rawName.size))
                    hdr.put(nameBytes)

                    val out = socket.getOutputStream()
                    out.write(hdr.array())
                    out.flush()

                    val buf = ByteArray(8192)
                    var sentBytes = 0
                    var lastPct = -1

                    FileInputStream(exndFile).use { fis ->
                        while (true) {
                            val r = fis.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            sentBytes += r
                            val pct = ((sentBytes.toLong() * 100L) / totalBytes.coerceAtLeast(1)).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    out.flush()

                    val reply = BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: "OK"
                    if (reply.startsWith("OK")) {
                        onResult(Result.success(reply))
                    } else {
                        onResult(Result.failure(Exception("Respuesta del reloj: $reply")))
                    }
                }
            } catch (t: Throwable) {
                onResult(Result.failure(Exception("Fallo TCP con el reloj ($watchIp:$WATCH_EXND_PORT): ${t.localizedMessage}")))
            }
        }
    }

    fun fetchWatchSdWallpapers(
        watchIp: String,
        onResult: (Result<List<RemoteWatchWallpaper>>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                val cleanIp = watchIp.trim().removePrefix("http://").substringBefore(":")
                if (cleanIp.isEmpty() || cleanIp == "0.0.0.0") return@thread onResult(Result.failure(Exception("Sin IP de reloj")))

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cleanIp, WATCH_EXND_PORT), 3500)
                    socket.soTimeout = 5000
                    val out = socket.getOutputStream()
                    out.write("EXLS".toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val list = ArrayList<RemoteWatchWallpaper>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "END") break
                        val p = line.split("|")
                        if (p.size >= 7) {
                            list.add(
                                RemoteWatchWallpaper(
                                    index = p[0].toIntOrNull() ?: 1,
                                    customName = p[1],
                                    width = p[2].toIntOrNull() ?: 205,
                                    height = p[3].toIntOrNull() ?: 251,
                                    frames = p[4].toIntOrNull() ?: 0,
                                    fps = p[5].toIntOrNull() ?: 15,
                                    sizeMb = (p[6].toLongOrNull() ?: 0L).toDouble() / (1024.0 * 1024.0)
                                )
                            )
                        }
                    }
                    onResult(Result.success(list))
                }
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }

    fun deleteWatchSdWallpaperCascade(
        watchIp: String,
        indexToDelete: Int,
        onResult: (Result<String>) -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                val cleanIp = watchIp.trim().removePrefix("http://").substringBefore(":")
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cleanIp, WATCH_EXND_PORT), 3500)
                    socket.soTimeout = 8000
                    val out = socket.getOutputStream()
                    val cmd = byteArrayOf(
                        'E'.code.toByte(), 'X'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(),
                        (indexToDelete and 0xFF).toByte()
                    )
                    out.write(cmd)
                    out.flush()
                    val resp = BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: "OK"
                    onResult(Result.success(resp))
                }
            } catch (t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }
}

// =============================================================================
// 3. VISTA INTERACTIVA DE RECORTE PROPORCIONAL (410:502 PARA EXND / 1:1 PARA MAPA)
// =============================================================================
class ExndCropOverlayView(
    context: Context,
    var targetAspectRatio: Float = ExndV32Compiler.TARGET_RATIO
) : View(context) {

    var onCropChanged: (() -> Unit)? = null
    private var frameBitmap: Bitmap? = null
    private val imgBounds = RectF()
    val cropBox = RectF()
    var cropMode: Int = 1 // 1 = Recorte Dinámico (Bounding Box), 2 = Letterboxing Central
        set(value) {
            field = value
            invalidate()
            onCropChanged?.invoke()
        }

    val normalizedCropRect: RectF
        get() {
            if (imgBounds.width() <= 1f || imgBounds.height() <= 1f) return RectF(0f, 0f, 1f, 1f)
            return RectF(
                ((cropBox.left - imgBounds.left) / imgBounds.width()).coerceIn(0f, 0.95f),
                ((cropBox.top - imgBounds.top) / imgBounds.height()).coerceIn(0f, 0.95f),
                ((cropBox.right - imgBounds.left) / imgBounds.width()).coerceIn(0.05f, 1f),
                ((cropBox.bottom - imgBounds.top) / imgBounds.height()).coerceIn(0.05f, 1f)
            )
        }

    private val paintBitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintDim = Paint().apply {
        color = Color.argb(165, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF41")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private var dragMode = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun setPreviewBitmap(bmp: Bitmap?) {
        frameBitmap = bmp
        requestLayout()
        post {
            resetCropBoxToCenter()
            onCropChanged?.invoke()
        }
    }

    fun resetCropBoxToCenter() {
        val bmp = frameBitmap ?: return
        if (width <= 0 || height <= 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = min(viewW / bmp.width.toFloat(), viewH / bmp.height.toFloat())
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (viewW - dw) * 0.5f
        val top = (viewH - dh) * 0.5f
        imgBounds.set(left, top, left + dw, top + dh)

        val ratio = targetAspectRatio.coerceAtLeast(0.1f)
        var cw = dw * 0.82f
        var ch = cw / ratio
        if (ch > dh * 0.90f) {
            ch = dh * 0.90f
            cw = ch * ratio
        }
        val cx = imgBounds.centerX()
        val cy = imgBounds.centerY()
        cropBox.set(cx - cw * 0.5f, cy - ch * 0.5f, cx + cw * 0.5f, cy + ch * 0.5f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetCropBoxToCenter()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#040A0D"))
        val bmp = frameBitmap ?: return

        canvas.drawBitmap(bmp, null, imgBounds, paintBitmap)

        if (cropMode == 1) {
            canvas.drawRect(imgBounds.left, imgBounds.top, imgBounds.right, cropBox.top, paintDim)
            canvas.drawRect(imgBounds.left, cropBox.bottom, imgBounds.right, imgBounds.bottom, paintDim)
            canvas.drawRect(imgBounds.left, cropBox.top, cropBox.left, cropBox.bottom, paintDim)
            canvas.drawRect(cropBox.right, cropBox.top, imgBounds.right, imgBounds.bottom, paintDim)

            canvas.drawRect(cropBox, paintBorder)
            canvas.drawCircle(cropBox.right, cropBox.bottom, 16f, paintCorner)
        } else {
            paintBorder.color = Color.parseColor("#00E5FF")
            canvas.drawRect(imgBounds, paintBorder)
            paintBorder.color = Color.parseColor("#00FF41")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cropMode != 1 || frameBitmap == null) return false
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val distCorner = hypot((x - cropBox.right).toDouble(), (y - cropBox.bottom).toDouble())
                dragMode = if (distCorner <= 48.0) {
                    2
                } else if (cropBox.contains(x, y)) {
                    1
                } else {
                    0
                }
                lastTouchX = x
                lastTouchY = y
                return dragMode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                lastTouchX = x
                lastTouchY = y

                if (dragMode == 1) {
                    val nx = (cropBox.left + dx).coerceIn(imgBounds.left, imgBounds.right - cropBox.width())
                    val ny = (cropBox.top + dy).coerceIn(imgBounds.top, imgBounds.bottom - cropBox.height())
                    cropBox.offsetTo(nx, ny)
                    invalidate()
                } else if (dragMode == 2) {
                    val ratio = targetAspectRatio.coerceAtLeast(0.1f)
                    val maxW = imgBounds.right - cropBox.left
                    val maxH = imgBounds.bottom - cropBox.top
                    var newW = (cropBox.width() + dx).coerceIn(60f, maxW)
                    var newH = newW / ratio
                    if (newH > maxH) {
                        newH = maxH
                        newW = newH * ratio
                    }
                    if (newW >= 50f && newH >= 50f) {
                        cropBox.right = cropBox.left + newW
                        cropBox.bottom = cropBox.top + newH
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMode = 0
                onCropChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

// =============================================================================
// 4. INTERFAZ UNIFICADA: SELECTOR INICIAL + FORJA DE FONDOS + PLACEHOLDER MAPA
// =============================================================================
object ExndWallpaperStudioUi {

    private val COLOR_PANEL = Color.parseColor("#0A1114")
    private val COLOR_CARD = Color.parseColor("#061418")
    private val COLOR_CYAN = Color.parseColor("#00E5FF")
    private val COLOR_GREEN = Color.parseColor("#00FF41")
    private val COLOR_AMBER = Color.parseColor("#FFB300")
    private val COLOR_DARK = Color.parseColor("#061A1E")
    private val COLOR_CRIT = Color.parseColor("#FF003C")

    private var pendingMediaCallback: ((Uri) -> Unit)? = null

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun onMediaUriSelected(context: Context, uri: Uri) {
        pendingMediaCallback?.invoke(uri)
    }

    // -------------------------------------------------------------------------
    // PASO 0: SELECTOR INICIAL AL PRESIONAR EL BOTÓN DE LA FORJA
    // -------------------------------------------------------------------------
    fun mostrarEstudioFondosDialog(
        context: Context,
        initialWatchIp: String,
        onLaunchMediaPicker: () -> Unit
    ) {
        val lang = ExonedForgeI18n.getLang(context)
        val idxLang = mapOf("es" to 0, "en" to 1, "zh" to 2, "ja" to 3, "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7)[lang] ?: 0

        val tTitle = arrayOf("ESTUDIO GRÁFICO EXOSYS —\nSELECCIONA MODO", "EXOSYS GRAPHIC STUDIO —\nSELECT MODE", "EXOSYS 图形工作室 —\n选择模式", "EXOSYS グラフィックスタジオ —\nモード選択", "EXOSYS ग्राफिक स्टूडियो —\nमोड चुनें", "STUDIO GRAPHIQUE EXOSYS —\nSÉLECTIONNER LE MODE", "استوديو رسومات EXOSYS —\nحدد الوضع", "ГРАФИЧЕСКАЯ СТУДИЯ EXOSYS —\nВЫБЕРИТЕ РЕЖИМ")[idxLang]
        val tSub = arrayOf("¿Qué deseas pre-procesar y enviar al reloj?", "What do you want to pre-process and send to the watch?", "您想预处理什么并将其发送到手表？", "時計に前処理して送信するものは何ですか？", "आप घड़ी पर क्या प्री-प्रोसेस करके भेजना चाहते हैं?", "Que souhaitez-vous pré-traiter et envoyer à la montre ?", "ما الذي ترغب في معالجته مسبقًا وإرساله إلى الساعة؟", "Что вы хотите обработать и отправить на часы?")[idxLang]

        val tOpt1Title = arrayOf("1. SUBIR FONDO DE PANTALLA (.EXND)", "1. UPLOAD WALLPAPER (.EXND)", "1. 上传壁纸 (.EXND)", "1. 壁紙のアップロード (.EXND)", "1. वॉलपेपर अपलोड करें (.EXND)", "1. TÉLÉCHARGER FOND D'ÉCRAN (.EXND)", "1. تحميل خلفية الشاشة (.EXND)", "1. ЗАГРУЗИТЬ ОБОИ (.EXND)")[idxLang]
        val tOpt1Desc = arrayOf("Pre-procesa videos (MP4/MOV), GIFs o imágenes en formato FSR 205x251 (Tope: 7.4 MB) y envíalos a la MicroSD del reloj.", "Pre-process videos (MP4/MOV), GIFs or images into FSR 205x251 format (Cap: 7.4 MB) and send them to the watch's MicroSD.", "将视频 (MP4/MOV)、GIF 或图像预处理为 FSR 205x251 格式 (上限：7.4 MB) 并发送到手表存储卡。", "動画(MP4/MOV)、GIF、画像をFSR 205x251形式に前処理し(上限7.4MB)、時計のMicroSDに送信します。", "FSR 205x251 प्रारूप (सीमा: 7.4 MB) में वीडियो, GIF या चित्र प्री-प्रोसेस करें और उन्हें घड़ी में भेजें।", "Pré-traitez des vidéos, GIFs ou images au format FSR 205x251 (Max: 7.4 Mo) vers la MicroSD.", "قم بمعالجة الفيديوهات أو الصور بصيغة FSR (الحد الأقصى: 7.4 ميجابايت) وأرسلها للساعة.", "Обработка видео, GIF или изображений в формат FSR (До 7.4 МБ) для отправки на MicroSD.")[idxLang]
        val tBtn1 = arrayOf("ABRIR FORJA DE FONDOS (.EXND) >>", "OPEN WALLPAPER FORGE (.EXND) >>", "打开壁纸制作室 (.EXND) >>", "壁紙作成工房を開く (.EXND) >>", "वॉलपेपर फ़ोर्ज खोलें (.EXND) >>", "OUVRIR FORGE FOND D'ÉCRAN >>", "فتح صانع الخلفيات >>", "ОТКРЫТЬ СТУДИЮ ОБОЕВ >>")[idxLang]

        val tOpt2Title = arrayOf("2. CAMBIAR PANTALLA DE CARGA DEL MAPA (256x256)", "2. CHANGE MAP LOADING SCREEN (256x256)", "2. 更改地图加载屏幕 (256x256)", "2. マップロード画面の変更 (256x256)", "2. नक्शा लोडिंग स्क्रीन बदलें (256x256)", "2. CHANGER L'ÉCRAN DE CHARGEMENT CARTE", "2. تغيير شاشة تحميل الخريطة (256x256)", "2. ИЗМЕНИТЬ ЭКРАН ЗАГРУЗКИ КАРТЫ (256x256)")[idxLang]
        val tOpt2Desc = arrayOf("Solo imagen estática (JPG/PNG/WEBP). Regulación garantizada <= 38 KB (.QOI) / 128 KB en PSRAM, con opción para restaurar la retícula por defecto.", "Static image only (JPG/PNG/WEBP). Guaranteed regulation <= 38 KB (.QOI) / 128 KB in PSRAM, with option to restore default reticle.", "仅限静态图像。保证调节 <= 38 KB (.QOI) / 128 KB PSRAM，并可恢复默认网格。", "静止画像のみ。PSRAM内で<= 38 KB (.QOI) / 128 KBに保証調整、デフォルトの十字線への復元オプション付き。", "केवल स्थिर चित्र। गारंटीकृत विनियमन <= 38 KB (.QOI), डिफ़ॉल्ट रेटिकल को पुनर्स्थापित करने के विकल्प के साथ।", "Image statique uniquement. Régulation garantie <= 38 Ko (.QOI), option pour restaurer le réticule.", "صورة ثابتة فقط. تنظيم مضمون <= 38 كيلوبايت، مع خيار استعادة الشبكة الافتراضية.", "Только стат. изображение. Гарантированное сжатие <= 38 КБ (.QOI), опция возврата сетки по умолчанию.")[idxLang]
        val tBtn2 = arrayOf("CAMBIAR PANTALLA DE CARGA DEL MAPA >>", "CHANGE MAP LOADING SCREEN >>", "更改地图加载屏幕 >>", "マップロード画面を変更 >>", "नक्शा लोडिंग स्क्रीन बदलें >>", "CHANGER L'ÉCRAN DE CHARGEMENT >>", "تغيير شاشة تحميل الخريطة >>", "ИЗМЕНИТЬ ЭКРАН ЗАГРУЗКИ КАРТЫ >>")[idxLang]
        val tBtnClose = arrayOf("CERRAR", "CLOSE", "关闭", "閉じる", "बंद करें", "FERMER", "إغلاق", "ЗАКРЫТЬ")[idxLang]

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_CYAN)
                cornerRadius = dp(context, 12).toFloat()
            }
        }

        val title = TextView(context).apply {
            text = tTitle
            setTextColor(COLOR_CYAN)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 6))
        }

        val subTitle = TextView(context).apply {
            text = tSub
            setTextColor(Color.LTGRAY)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 14))
        }

        var selectorDlg: AlertDialog? = null

        val cardWallpaper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 2), COLOR_GREEN)
                cornerRadius = dp(context, 8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }
        val lblWallTitle = TextView(context).apply {
            text = tOpt1Title
            setTextColor(COLOR_GREEN)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val lblWallDesc = TextView(context).apply {
            text = tOpt1Desc
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(0, dp(context, 4), 0, dp(context, 10))
        }
        val btnGoWallpaper = Button(context).apply {
            text = tBtn1
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setOnClickListener {
                selectorDlg?.dismiss()
                mostrarForjaExndDialog(context, initialWatchIp, onLaunchMediaPicker)
            }
        }
        cardWallpaper.addView(lblWallTitle)
        cardWallpaper.addView(lblWallDesc)
        cardWallpaper.addView(btnGoWallpaper)

        val cardPlaceholder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 2), COLOR_AMBER)
                cornerRadius = dp(context, 8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }
        val lblPhTitle = TextView(context).apply {
            text = tOpt2Title
            setTextColor(COLOR_AMBER)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val lblPhDesc = TextView(context).apply {
            text = tOpt2Desc
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(0, dp(context, 4), 0, dp(context, 10))
        }
        val btnGoPlaceholder = Button(context).apply {
            text = tBtn2
            setBackgroundColor(COLOR_AMBER)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setOnClickListener {
                selectorDlg?.dismiss()
                mostrarForjaPlaceholderMapaDialog(context, initialWatchIp, onLaunchMediaPicker)
            }
        }
        cardPlaceholder.addView(lblPhTitle)
        cardPlaceholder.addView(lblPhDesc)
        cardPlaceholder.addView(btnGoPlaceholder)

        val btnCancel = Button(context).apply {
            text = tBtnClose
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setOnClickListener { selectorDlg?.dismiss() }
        }

        root.addView(title)
        root.addView(subTitle)
        root.addView(cardWallpaper)
        root.addView(cardPlaceholder)
        root.addView(btnCancel)

        selectorDlg = AlertDialog.Builder(context).setView(root).create()
        selectorDlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        selectorDlg.show()
    }

    // -------------------------------------------------------------------------
    // MODO 2: PANTALLA DE CARGA DEL MAPA (SOLO IMAGEN ESTÁTICA 256x256 <= 38 KB)
    // -------------------------------------------------------------------------
    private fun mostrarForjaPlaceholderMapaDialog(
        context: Context,
        initialWatchIp: String,
        onLaunchMediaPicker: () -> Unit
    ) {
        val lang = ExonedForgeI18n.getLang(context)
        val idxLang = mapOf("es" to 0, "en" to 1, "zh" to 2, "ja" to 3, "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7)[lang] ?: 0

        val tTitle = arrayOf("PANTALLA DE CARGA (256×256)", "MAP LOADING SCREEN (256×256)", "地图加载屏幕 (256×256)", "マップロード画面 (256×256)", "नक्शा लोडिंग स्क्रीन (256×256)", "ÉCRAN DE CHARGEMENT (256×256)", "شاشة تحميل الخريطة (256×256)", "ЭКРАН ЗАГРУЗКИ (256×256)")[idxLang]
        val tSwitchExnd = arrayOf("⇄ FONDOS .EXND", "⇄ .EXND WALLPAPERS", "⇄ .EXND 壁纸", "⇄ .EXND 壁紙", "⇄ .EXND वॉलपेपर", "⇄ FONDS .EXND", "⇄ خلفيات .EXND", "⇄ ОБОИ .EXND")[idxLang]
        val tIpLabel = arrayOf("IP Reloj:", "Watch IP:", "手表 IP:", "時計のIP:", "घड़ी IP:", "IP Montre :", "IP الساعة:", "IP часов:")[idxLang]
        val tPickImg = arrayOf("1. SELECCIONAR IMAGEN ESTÁTICA", "1. SELECT STATIC IMAGE", "1. 选择静态图像", "1. 静止画像を選択", "1. स्थिर चित्र चुनें", "1. SÉLECTIONNER IMAGE STATIQUE", "1. حدد صورة ثابتة", "1. ВЫБРАТЬ ИЗОБРАЖЕНИЕ")[idxLang]
        val tNameHint = arrayOf("Nombre del archivo (ej. RANA_MATRIZ)", "File name (e.g. FROG_MATRIX)", "文件名 (例：FROG_MATRIX)", "ファイル名 (例: FROG_MATRIX)", "फ़ाइल का नाम (उदा. FROG_MATRIX)", "Nom de fichier (ex. GRENOUILLE)", "اسم الملف (مثل FROG_MATRIX)", "Имя файла (напр. FROG_MATRIX)")[idxLang]
        val tCropSquare = arrayOf("RECORTE CUADRADO 1:1", "1:1 SQUARE CROP", "1:1 方形裁剪", "1:1 正方形クロップ", "1:1 चौकोर क्रॉप", "RECADRAGE CARRÉ 1:1", "قص مربع 1:1", "КВАДРАТ 1:1")[idxLang]
        val tCropLetter = arrayOf("ENCUADRE COMPLETO", "FULL FRAME (LETTERBOX)", "全画幅 (居中)", "フルフレーム (レターボックス)", "पूर्ण फ्रेम (लेटरबॉक्स)", "CADRE COMPLET", "إطار كامل", "ПОЛНЫЙ КАДР")[idxLang]
        val tWarnText = arrayOf("Elige cualquier imagen estática. Se comprimirá dentro del margen seguro (<= 38 KB).", "Choose any static image. It will compress within the safe margin (<= 38 KB).", "选择静态图像，将压缩在安全范围内 (<= 38 KB)。", "静止画像を選択してください。安全な範囲 (<= 38 KB) に圧縮されます。", "कोई भी स्थिर चित्र चुनें। सुरक्षित सीमा (<= 38 KB) में संपीड़ित होगा।", "Choisissez une image statique. Compression garantie <= 38 Ko.", "اختر أي صورة ثابتة. سيتم ضغطها ضمن الهامش الآمن (<= 38 كيلوبايت).", "Выберите стат. изображение. Сжатие в пределах нормы (<= 38 КБ).")[idxLang]
        val tCompilePh = arrayOf("2. ENVIAR PANTALLA AL RELOJ", "2. SEND SCREEN TO WATCH", "2. 发送屏幕到手表", "2. 画面を時計に送信", "2. स्क्रीन घड़ी पर भेजें", "2. ENVOYER L'ÉCRAN À LA MONTRE", "2. إرسال الشاشة للساعة", "2. ОТПРАВИТЬ ЭКРАН НА ЧАСЫ")[idxLang]
        val tRestoreDef = arrayOf("↺ VOLVER A IMAGEN POR DEFECTO", "↺ RESTORE DEFAULT IMAGE", "↺ 恢复默认图像", "↺ デフォルト画像に戻す", "↺ डिफ़ॉल्ट चित्र पुनर्स्थापित करें", "↺ RESTAURER L'IMAGE DÉFAUT", "↺ استعادة الصورة الافتراضية", "↺ ВЕРНУТЬ ПО УМОЛЧАНИЮ")[idxLang]
        val tSavedTitle = arrayOf("PANTALLAS GUARDADAS:", "SAVED SCREENS:", "已保存屏幕：", "保存された画面:", "सहेजी गई स्क्रीन:", "ÉCRANS SAUVEGARDÉS :", "الشاشات المحفوظة:", "СОХРАНЁННЫЕ ЭКРАНЫ:")[idxLang]
        val tBtnClose = arrayOf("CERRAR", "CLOSE", "关闭", "閉じる", "बंद करें", "FERMER", "إغلاق", "ЗАКРЫТЬ")[idxLang]

        val mainHandler = Handler(Looper.getMainLooper())
        val isBusy = AtomicBoolean(false)
        var sourceBmp: Bitmap? = null
        var dlg: AlertDialog? = null

        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_AMBER)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        scroll.addView(root)

        val topHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        val titleView = TextView(context).apply {
            text = tTitle
            setTextColor(COLOR_AMBER)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSwitchToExnd = Button(context).apply {
            text = tSwitchExnd
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_GREEN)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                if (isBusy.get()) return@setOnClickListener
                dlg?.dismiss()
                mostrarForjaExndDialog(context, initialWatchIp, onLaunchMediaPicker)
            }
        }
        topHeaderRow.addView(titleView)
        topHeaderRow.addView(btnSwitchToExnd)

        val ipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        val lblIp = TextView(context).apply {
            text = tIpLabel
            setTextColor(COLOR_CYAN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(context, 6), 0)
        }
        val edtWatchIp = EditText(context).apply {
            hint = "Ej. 192.168.43.50"
            setText(initialWatchIp)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipRow.addView(lblIp)
        ipRow.addView(edtWatchIp)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 1), COLOR_AMBER)
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }

        val btnPickImage = Button(context).apply {
            text = tPickImg
            setBackgroundColor(COLOR_AMBER)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
        }

        val edtCustomName = EditText(context).apply {
            hint = tNameHint
            setText("PLACEHOLDER_MAPA")
            setTextColor(COLOR_AMBER)
            setHintTextColor(Color.GRAY)
            textSize = 12f
        }

        val cropView = ExndCropOverlayView(context, targetAspectRatio = 1.0f).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 240)).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            }
            visibility = View.GONE
        }

        val cropModeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(context, 6))
        }
        val btnModeBox = Button(context).apply {
            text = tCropSquare
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 34), 1f).apply { rightMargin = dp(context, 4) }
        }
        val btnModeLetter = Button(context).apply {
            text = tCropLetter
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 34), 1f)
        }
        cropModeRow.addView(btnModeBox)
        cropModeRow.addView(btnModeLetter)

        val livePreviewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 6))
        }
        val imgLive256 = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 76), dp(context, 76)).apply {
                rightMargin = dp(context, 10)
            }
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val txtLiveInfo = TextView(context).apply {
            text = ""
            setTextColor(COLOR_CYAN)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        livePreviewRow.addView(imgLive256)
        livePreviewRow.addView(txtLiveInfo)

        fun updateRegulatedPreview() {
            val bmp = sourceBmp ?: return
            val reg = MapPlaceholderCompiler.preProcessAndRegulate256(
                srcBmp = bmp,
                cropMode = cropView.cropMode,
                normCrop = cropView.normalizedCropRect
            )
            imgLive256.setImageBitmap(reg.previewBitmap)
            val kb = reg.qoiBytes.size / 1024.0
            txtLiveInfo.text = String.format(
                Locale.US,
                "256×256 RGB565 (CL%d)\n%.1f KB (Max <= 38.0 KB)",
                reg.crushLevel,
                kb
            )
            livePreviewRow.visibility = View.VISIBLE
        }

        cropView.onCropChanged = { updateRegulatedPreview() }
        btnModeBox.setOnClickListener {
            cropView.cropMode = 1
            btnModeBox.setBackgroundColor(COLOR_GREEN)
            btnModeBox.setTextColor(Color.BLACK)
            btnModeLetter.setBackgroundColor(COLOR_DARK)
            btnModeLetter.setTextColor(COLOR_CYAN)
        }
        btnModeLetter.setOnClickListener {
            cropView.cropMode = 2
            btnModeLetter.setBackgroundColor(COLOR_CYAN)
            btnModeLetter.setTextColor(Color.BLACK)
            btnModeBox.setBackgroundColor(COLOR_DARK)
            btnModeBox.setTextColor(COLOR_GREEN)
        }

        val txtStatus = TextView(context).apply {
            text = tWarnText
            setTextColor(COLOR_AMBER)
            textSize = 11.5f
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val pbProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 10)).apply {
                bottomMargin = dp(context, 8)
            }
        }

        val btnCompileAndSendPh = Button(context).apply {
            text = tCompilePh
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 8)
            }
        }

        val btnRestoreDefaultPh = Button(context).apply {
            text = tRestoreDef
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f
            setOnClickListener {
                if (isBusy.get()) return@setOnClickListener
                val watchIp = edtWatchIp.text.toString().trim()
                if (watchIp.isEmpty() || watchIp == "0.0.0.0") {
                    txtStatus.setTextColor(COLOR_CRIT)
                    txtStatus.text = "IP missing / Faltante IP"
                    return@setOnClickListener
                }
                isBusy.set(true)
                pbProgress.progress = 20
                txtStatus.setTextColor(COLOR_CYAN)
                txtStatus.text = "Restoring default grid / Restaurando retícula..."

                thread(start = true, isDaemon = true) {
                    val defaultQoiFile = MapPlaceholderCompiler.buildDefaultTacticalGridQoiFile(context)
                    ExndWatchTcpBridge.uploadExndToWatchSd(
                        watchIp = watchIp,
                        exndFile = defaultQoiFile,
                        customName = "placeholder.qoi",
                        targetSlot = ExndWatchTcpBridge.SLOT_MAP_PLACEHOLDER_QOI,
                        activateNow = true,
                        onProgress = { pct ->
                            mainHandler.post { pbProgress.progress = 20 + (pct * 80 / 100) }
                        },
                        onResult = { res ->
                            mainHandler.post {
                                isBusy.set(false)
                                pbProgress.progress = 100
                                res.onSuccess { reply ->
                                    txtStatus.setTextColor(COLOR_GREEN)
                                    txtStatus.text = "[OK] Default grid restored ($reply)."
                                }.onFailure { err ->
                                    txtStatus.setTextColor(COLOR_CRIT)
                                    txtStatus.text = "[ERROR TCP] ${err.localizedMessage}"
                                }
                            }
                        }
                    )
                }
            }
        }

        val savedPhVaultContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun refreshSavedPlaceholdersVault() {
            savedPhVaultContainer.removeAllViews()
            val dir = File(context.getExternalFilesDir(null), "EXOSYS_PLACEHOLDERS")
            val files = dir.listFiles()?.filter {
                it.name.endsWith(".qoi", ignoreCase = true) && !it.name.startsWith("DEFAULT_RETICULA")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            if (files.isEmpty()) {
                savedPhVaultContainer.addView(TextView(context).apply {
                    text = "Empty / Vacío"
                    setTextColor(Color.GRAY)
                    textSize = 11f
                })
                return
            }

            for (qf in files) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
                    background = GradientDrawable().apply {
                        setColor(COLOR_CARD)
                        setStroke(dp(context, 1), COLOR_DARK)
                        cornerRadius = dp(context, 6).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(context, 6) }
                }

                val bmp = MapPlaceholderCompiler.decodeSavedPlaceholderPreview(qf)
                val img = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 46), dp(context, 46)).apply {
                        rightMargin = dp(context, 8)
                    }
                    if (bmp != null) setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_XY
                }

                val kb = qf.length() / 1024.0
                val lbl = TextView(context).apply {
                    text = "${qf.nameWithoutExtension}\n(${String.format(Locale.US, "%.1f KB", kb)})"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnSendWatch = Button(context).apply {
                    text = "=> SD"
                    setBackgroundColor(COLOR_AMBER)
                    setTextColor(Color.BLACK)
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setOnClickListener {
                        if (isBusy.get()) return@setOnClickListener
                        val wIp = edtWatchIp.text.toString().trim()
                        isBusy.set(true)
                        txtStatus.setTextColor(COLOR_CYAN)
                        txtStatus.text = "Sending / Enviando a $wIp..."
                        thread(start = true, isDaemon = true) {
                            val safeFile = MapPlaceholderCompiler.ensureSafeQoiFile(context, qoiFile = qf)
                            ExndWatchTcpBridge.uploadExndToWatchSd(
                                watchIp = wIp,
                                exndFile = safeFile,
                                customName = "placeholder.qoi",
                                targetSlot = ExndWatchTcpBridge.SLOT_MAP_PLACEHOLDER_QOI,
                                activateNow = true,
                                onProgress = { pct ->
                                    mainHandler.post { pbProgress.progress = pct }
                                },
                                onResult = { res ->
                                    mainHandler.post {
                                        isBusy.set(false)
                                        refreshSavedPlaceholdersVault()
                                        res.onSuccess { msg ->
                                            txtStatus.setTextColor(COLOR_GREEN)
                                            txtStatus.text = "[OK] Sent to SD ($msg)."
                                        }.onFailure { err ->
                                            txtStatus.setTextColor(COLOR_CRIT)
                                            txtStatus.text = "[ERROR TCP] ${err.localizedMessage}"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                val btnDel = Button(context).apply {
                    text = "X"
                    setBackgroundColor(COLOR_CRIT)
                    setTextColor(Color.WHITE)
                    textSize = 9.5f
                    layoutParams = LinearLayout.LayoutParams(dp(context, 38), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(context, 4)
                    }
                    setOnClickListener {
                        File(dir, "${qf.nameWithoutExtension}.bin").delete()
                        qf.delete()
                        refreshSavedPlaceholdersVault()
                    }
                }

                row.addView(img)
                row.addView(lbl)
                row.addView(btnSendWatch)
                row.addView(btnDel)
                savedPhVaultContainer.addView(row)
            }
        }

        pendingMediaCallback = { uri ->
            thread(start = true, isDaemon = true) {
                val res = MapPlaceholderCompiler.decodeStaticImageOnly(context, uri)
                mainHandler.post {
                    res.onSuccess { bmp ->
                        sourceBmp?.recycle()
                        sourceBmp = bmp
                        cropView.visibility = View.VISIBLE
                        cropModeRow.visibility = View.VISIBLE
                        cropView.setPreviewBitmap(bmp)
                        btnCompileAndSendPh.isEnabled = true
                        updateRegulatedPreview()
                        txtStatus.setTextColor(COLOR_GREEN)
                        txtStatus.text = "OK"
                    }.onFailure { err ->
                        txtStatus.setTextColor(COLOR_CRIT)
                        txtStatus.text = err.localizedMessage ?: "File Error"
                    }
                }
            }
        }

        btnPickImage.setOnClickListener {
            if (isBusy.get()) return@setOnClickListener
            onLaunchMediaPicker()
        }

        btnCompileAndSendPh.setOnClickListener {
            val bmp = sourceBmp ?: return@setOnClickListener
            if (isBusy.get()) return@setOnClickListener
            isBusy.set(true)
            btnCompileAndSendPh.isEnabled = false

            val cMode = cropView.cropMode
            val nRect = cropView.normalizedCropRect
            val customName = edtCustomName.text.toString().trim().ifBlank { "PLACEHOLDER_MAPA" }
            val watchIp = edtWatchIp.text.toString().trim()

            thread(start = true, isDaemon = true) {
                mainHandler.post {
                    pbProgress.progress = 35
                    txtStatus.setTextColor(COLOR_CYAN)
                    txtStatus.text = "Pre-processing / Procesando..."
                }

                val compiled = MapPlaceholderCompiler.compileAndSavePlaceholder(
                    context = context,
                    customName = customName,
                    srcBmp = bmp,
                    cropMode = cMode,
                    normCrop = nRect
                )

                val qoiKb = compiled.qoiSizeBytes / 1024.0
                if (watchIp.isNotEmpty() && watchIp != "0.0.0.0") {
                    ExndWatchTcpBridge.uploadExndToWatchSd(
                        watchIp = watchIp,
                        exndFile = compiled.qoiFile,
                        customName = "placeholder.qoi",
                        targetSlot = ExndWatchTcpBridge.SLOT_MAP_PLACEHOLDER_QOI,
                        activateNow = true,
                        onProgress = { pct ->
                            mainHandler.post {
                                pbProgress.progress = 50 + (pct / 2)
                                txtStatus.text = "Sending / Enviando ($pct%)..."
                            }
                        },
                        onResult = { res ->
                            mainHandler.post {
                                isBusy.set(false)
                                btnCompileAndSendPh.isEnabled = true
                                pbProgress.progress = 100
                                refreshSavedPlaceholdersVault()
                                res.onSuccess { reply ->
                                    txtStatus.setTextColor(COLOR_GREEN)
                                    txtStatus.text = "[OK] Saved and active in Watch SD ($reply)."
                                }.onFailure { err ->
                                    txtStatus.setTextColor(COLOR_AMBER)
                                    txtStatus.text = "Processed but Watch offline: ${err.localizedMessage}"
                                }
                            }
                        }
                    )
                } else {
                    mainHandler.post {
                        isBusy.set(false)
                        btnCompileAndSendPh.isEnabled = true
                        pbProgress.progress = 100
                        refreshSavedPlaceholdersVault()
                        txtStatus.setTextColor(COLOR_GREEN)
                        txtStatus.text = "[PRE-PROCESSED] Ready to send. / Listo para enviar."
                    }
                }
            }
        }

        card.addView(btnPickImage)
        card.addView(edtCustomName)
        card.addView(cropView)
        card.addView(cropModeRow)
        card.addView(livePreviewRow)
        card.addView(txtStatus)
        card.addView(pbProgress)
        card.addView(btnCompileAndSendPh)
        card.addView(btnRestoreDefaultPh)

        val lblVault = TextView(context).apply {
            text = tSavedTitle
            setTextColor(COLOR_AMBER)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val btnClose = Button(context).apply {
            text = tBtnClose
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 10)
            }
            setOnClickListener { dlg?.dismiss() }
        }

        root.addView(topHeaderRow)
        root.addView(ipRow)
        root.addView(card)
        root.addView(lblVault)
        root.addView(savedPhVaultContainer)
        root.addView(btnClose)

        refreshSavedPlaceholdersVault()

        dlg = AlertDialog.Builder(context).setView(scroll).setOnDismissListener {
            pendingMediaCallback = null
            sourceBmp?.recycle()
            sourceBmp = null
        }.create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }

    // -------------------------------------------------------------------------
    // MODO 1: FORJA DE FONDOS ANIMADOS V32 (.EXND — 205x251, TOPE 7.4 MB)
    // -------------------------------------------------------------------------
    private fun mostrarForjaExndDialog(
        context: Context,
        initialWatchIp: String,
        onLaunchMediaPicker: () -> Unit
    ) {
        val lang = ExonedForgeI18n.getLang(context)
        val idxLang = mapOf("es" to 0, "en" to 1, "zh" to 2, "ja" to 3, "hi" to 4, "fr" to 5, "ar" to 6, "ru" to 7)[lang] ?: 0

        val tTitle = arrayOf("FORJA DE FONDOS V32 (.EXND)", "WALLPAPER FORGE V32 (.EXND)", "壁纸制作室 V32 (.EXND)", "壁紙工房 V32 (.EXND)", "वॉलपेपर फ़ोर्ज V32 (.EXND)", "FORGE FOND D'ÉCRAN V32", "صانع الخلفيات V32 (.EXND)", "СТУДИЯ ОБОЕВ V32 (.EXND)")[idxLang]
        val tSwitchPh = arrayOf("⇄ CARGA MAPA 256×256", "⇄ MAP LOAD 256×256", "⇄ 地图加载 256×256", "⇄ マップロード 256×256", "⇄ नक्शा लोड 256×256", "⇄ CHARGEMENT CARTE", "⇄ تحميل الخريطة 256×256", "⇄ ЗАГРУЗКА КАРТЫ 256×256")[idxLang]
        val tIpLabel = arrayOf("IP Reloj:", "Watch IP:", "手表 IP:", "時計のIP:", "घड़ी IP:", "IP Montre :", "IP الساعة:", "IP часов:")[idxLang]
        val tPickMedia = arrayOf("1. SELECCIONAR VIDEO/GIF/IMAGEN", "1. SELECT VIDEO/GIF/IMAGE", "1. 选择 视频/GIF/图像", "1. 動画/GIF/画像を選択", "1. वीडियो/GIF/चित्र चुनें", "1. SÉLECTIONNER VIDÉO/GIF/IMAGE", "1. حدد فيديو/GIF/صورة", "1. ВЫБРАТЬ ВИДЕО/GIF/ИЗОБРАЖЕНИЕ")[idxLang]
        val tNameHint = arrayOf("Nombre (ej. Cyberpunk)", "Name (e.g. Cyberpunk)", "名称 (例：Cyberpunk)", "名前 (例: Cyberpunk)", "नाम (उदा. Cyberpunk)", "Nom (ex. Cyberpunk)", "الاسم (مثل Cyberpunk)", "Имя (напр. Cyberpunk)")[idxLang]
        val tCropDynamic = arrayOf("RECORTE DINÁMICO (CAJA)", "DYNAMIC CROP (BOX)", "动态裁剪 (框)", "ダイナミッククロップ (ボックス)", "गतिशील क्रॉप (बॉक्स)", "RECADRAGE DYNAMIQUE", "قص ديناميكي", "ДИНАМИЧНЫЙ КРОП")[idxLang]
        val tCropLetter = arrayOf("LETTERBOX CENTRAL", "CENTER LETTERBOX", "居中 (上下黑边)", "中央レターボックス", "केंद्र लेटरबॉक्स", "LETTERBOX CENTRAL", "صندوق مركزي", "ЦЕНТРАЛЬНЫЙ LETTERBOX")[idxLang]
        val tFpsLabel = arrayOf("FPS Tope:", "Max FPS:", "最高 FPS:", "最大 FPS:", "अधिकतम FPS:", "Max FPS :", "أقصى FPS:", "Макс FPS:")[idxLang]
        val tActivate = arrayOf("Activar fondo en el reloj al terminar", "Activate on watch when finished", "完成后在手表上激活", "完了時に時計でアクティブにする", "पूरा होने पर घड़ी में सक्रिय करें", "Activer sur la montre à la fin", "تنشيط في الساعة عند الانتهاء", "Активировать на часах по завершении")[idxLang]
        val tWarnText = arrayOf("Selecciona archivo. Máx 7.4 MB tras compresión.", "Select file. Max 7.4 MB after compression.", "选择文件。压缩后最大 7.4 MB。", "ファイルを選択。圧縮後最大 7.4 MB。", "फ़ाइल चुनें। संपीड़न के बाद अधिकतम 7.4 MB.", "Sélectionnez fichier. Max 7.4 Mo après compression.", "حدد ملف. الحد الأقصى 7.4 ميجابايت بعد الضغط.", "Выберите файл. Макс 7.4 МБ после сжатия.")[idxLang]
        val tCompileExnd = arrayOf("2. COMPILAR Y ENVIAR AL RELOJ", "2. COMPILE & SEND TO WATCH", "2. 编译并发送到手表", "2. コンパイルして時計に送信", "2. संकलित करें और घड़ी पर भेजें", "2. COMPILER & ENVOYER À LA MONTRE", "2. تجميع وإرسال للساعة", "2. СОБРАТЬ И ОТПРАВИТЬ НА ЧАСЫ")[idxLang]
        val tLocVault = arrayOf("FONDOS .EXND LOCALES:", "LOCAL .EXND WALLPAPERS:", "本地 .EXND 壁纸：", "ローカル .EXND 壁紙:", "स्थानीय .EXND वॉलपेपर:", "FONDS .EXND LOCAUX :", "خلفيات .EXND المحلية:", "ЛОКАЛЬНЫЕ ОБОИ .EXND:")[idxLang]
        val tRemVault = arrayOf("BÓVEDA MICRO-SD DEL RELOJ:", "WATCH MICRO-SD VAULT:", "手表 MICRO-SD 库：", "時計 MICRO-SD 保管庫:", "घड़ी MICRO-SD वॉल्ट:", "VOÛTE MICRO-SD DE LA MONTRE :", "خزنة MICRO-SD للساعة:", "ХРАНИЛИЩЕ MICRO-SD ЧАСОВ:")[idxLang]
        val tBtnRefresh = arrayOf("LEER SD RELOJ", "READ WATCH SD", "读取手表 SD", "時計のSDを読み込む", "घड़ी SD पढ़ें", "LIRE SD MONTRE", "قراءة SD الساعة", "ЧИТАТЬ SD ЧАСОВ")[idxLang]
        val tBtnClose = arrayOf("CERRAR ESTUDIO", "CLOSE STUDIO", "关闭工作室", "スタジオを閉じる", "स्टूडियो बंद करें", "FERMER LE STUDIO", "إغلاق الاستوديو", "ЗАКРЫТЬ СТУДИЮ")[idxLang]

        val mainHandler = Handler(Looper.getMainLooper())
        val isBusy = AtomicBoolean(false)
        var selectedUri: Uri? = null
        var firstFrameBmp: Bitmap? = null
        var dlg: AlertDialog? = null

        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                setStroke(dp(context, 2), COLOR_GREEN)
                cornerRadius = dp(context, 10).toFloat()
            }
        }
        scroll.addView(root)

        val topHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        val titleView = TextView(context).apply {
            text = tTitle
            setTextColor(COLOR_GREEN)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSwitchToPh = Button(context).apply {
            text = tSwitchPh
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_AMBER)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                if (isBusy.get()) return@setOnClickListener
                dlg?.dismiss()
                mostrarForjaPlaceholderMapaDialog(context, initialWatchIp, onLaunchMediaPicker)
            }
        }
        topHeaderRow.addView(titleView)
        topHeaderRow.addView(btnSwitchToPh)

        val ipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 8))
        }
        val lblIp = TextView(context).apply {
            text = tIpLabel
            setTextColor(COLOR_CYAN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(context, 6), 0)
        }
        val edtWatchIp = EditText(context).apply {
            hint = "Ej. 192.168.43.50"
            setText(initialWatchIp)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 12.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipRow.addView(lblIp)
        ipRow.addView(edtWatchIp)

        val forgeCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                setStroke(dp(context, 1), COLOR_CYAN)
                cornerRadius = dp(context, 6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 10) }
        }

        val btnPickVideo = Button(context).apply {
            text = tPickMedia
            setBackgroundColor(COLOR_CYAN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
        }

        val edtCustomName = EditText(context).apply {
            hint = tNameHint
            setTextColor(COLOR_GREEN)
            setHintTextColor(Color.GRAY)
            textSize = 12f
        }

        val cropView = ExndCropOverlayView(context, targetAspectRatio = ExndV32Compiler.TARGET_RATIO).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 250)).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            }
            visibility = View.GONE
        }

        val cropModeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(context, 6))
        }

        val btnModeBox = Button(context).apply {
            text = tCropDynamic
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 34), 1f).apply { rightMargin = dp(context, 4) }
        }

        val btnModeLetter = Button(context).apply {
            text = tCropLetter
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_CYAN)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 34), 1f)
        }

        btnModeBox.setOnClickListener {
            cropView.cropMode = 1
            btnModeBox.setBackgroundColor(COLOR_GREEN)
            btnModeBox.setTextColor(Color.BLACK)
            btnModeLetter.setBackgroundColor(COLOR_DARK)
            btnModeLetter.setTextColor(COLOR_CYAN)
        }
        btnModeLetter.setOnClickListener {
            cropView.cropMode = 2
            btnModeLetter.setBackgroundColor(COLOR_CYAN)
            btnModeLetter.setTextColor(Color.BLACK)
            btnModeBox.setBackgroundColor(COLOR_DARK)
            btnModeBox.setTextColor(COLOR_GREEN)
        }
        cropModeRow.addView(btnModeBox)
        cropModeRow.addView(btnModeLetter)

        val optsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 4), 0, dp(context, 6))
        }
        val lblFps = TextView(context).apply {
            text = tFpsLabel
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(0, 0, dp(context, 6), 0)
        }
        val fpsOptions = arrayOf("24 FPS", "20 FPS", "15 FPS", "30 FPS")
        val fpsValues = intArrayOf(24, 20, 15, 30)
        val spnFps = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
            setSelection(0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        optsRow.addView(lblFps)
        optsRow.addView(spnFps)

        val cbActivateOnWatch = CheckBox(context).apply {
            text = tActivate
            setTextColor(Color.WHITE)
            textSize = 11f
            isChecked = true
        }

        val txtForgeStatus = TextView(context).apply {
            text = tWarnText
            setTextColor(COLOR_AMBER)
            textSize = 11.5f
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val pbForge = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 10)).apply {
                bottomMargin = dp(context, 8)
            }
        }

        val btnCompileAndSend = Button(context).apply {
            text = tCompileExnd
            setBackgroundColor(COLOR_GREEN)
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11.5f
            isEnabled = false
        }

        pendingMediaCallback = { uri ->
            selectedUri = uri
            thread(start = true, isDaemon = true) {
                val preview = MapPlaceholderCompiler.extractPreviewBitmapAnyMedia(context, uri)
                mainHandler.post {
                    firstFrameBmp?.recycle()
                    firstFrameBmp = preview
                    if (preview != null) {
                        cropView.visibility = View.VISIBLE
                        cropModeRow.visibility = View.VISIBLE
                        cropView.setPreviewBitmap(preview)
                        btnCompileAndSend.isEnabled = true
                        if (edtCustomName.text.isNullOrBlank()) {
                            edtCustomName.setText("AW_${System.currentTimeMillis() % 1000}")
                        }
                        txtForgeStatus.setTextColor(COLOR_GREEN)
                        txtForgeStatus.text = "OK"
                    } else {
                        txtForgeStatus.setTextColor(COLOR_CRIT)
                        txtForgeStatus.text = "Error reading file / Error leyendo archivo."
                    }
                }
            }
        }

        btnPickVideo.setOnClickListener {
            if (isBusy.get()) return@setOnClickListener
            onLaunchMediaPicker()
        }

        val localVaultContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val remoteWatchVaultContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun refreshVaults() {
            localVaultContainer.removeAllViews()
            val dir = File(context.getExternalFilesDir(null), "EXOSYS_WALLPAPERS")
            val files = dir.listFiles()?.filter { it.name.endsWith(".exnd", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()

            for (f in files) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
                    background = GradientDrawable().apply {
                        setColor(COLOR_CARD)
                        setStroke(dp(context, 1), COLOR_DARK)
                        cornerRadius = dp(context, 6).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(context, 6) }
                }

                val bmp = ExndV32Compiler.decodeExndHeaderPreview(f)
                val img = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 52)).apply {
                        rightMargin = dp(context, 8)
                    }
                    if (bmp != null) setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }

                val mb = f.length().toDouble() / (1024.0 * 1024.0)
                val lbl = TextView(context).apply {
                    text = "${f.nameWithoutExtension}\n${String.format(Locale.US, "%.2f MB", mb)}"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnPush = Button(context).apply {
                    text = "=> SD"
                    setBackgroundColor(COLOR_CYAN)
                    setTextColor(Color.BLACK)
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setOnClickListener {
                        if (isBusy.get()) return@setOnClickListener
                        isBusy.set(true)
                        val wIp = edtWatchIp.text.toString().trim()
                        txtForgeStatus.setTextColor(COLOR_CYAN)
                        txtForgeStatus.text = "Sending / Enviando a $wIp..."
                        ExndWatchTcpBridge.uploadExndToWatchSd(
                            watchIp = wIp,
                            exndFile = f,
                            customName = f.nameWithoutExtension,
                            targetSlot = 0,
                            activateNow = cbActivateOnWatch.isChecked,
                            onProgress = { pct ->
                                mainHandler.post {
                                    pbForge.progress = pct
                                    txtForgeStatus.text = "Sending / Enviando: $pct%..."
                                }
                            },
                            onResult = { res ->
                                mainHandler.post {
                                    isBusy.set(false)
                                    res.onSuccess { msg ->
                                        txtForgeStatus.setTextColor(COLOR_GREEN)
                                        txtForgeStatus.text = "[OK] Saved in SD / Guardado en SD ($msg)."
                                        refreshVaults()
                                    }.onFailure { err ->
                                        txtForgeStatus.setTextColor(COLOR_CRIT)
                                        txtForgeStatus.text = "[ERROR TCP] ${err.localizedMessage}"
                                    }
                                }
                            }
                        )
                    }
                }

                val btnDelLocal = Button(context).apply {
                    text = "X"
                    setBackgroundColor(COLOR_CRIT)
                    setTextColor(Color.WHITE)
                    textSize = 9.5f
                    layoutParams = LinearLayout.LayoutParams(dp(context, 38), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(context, 4)
                    }
                    setOnClickListener {
                        f.delete()
                        refreshVaults()
                    }
                }

                row.addView(img)
                row.addView(lbl)
                row.addView(btnPush)
                row.addView(btnDelLocal)
                localVaultContainer.addView(row)
            }

            remoteWatchVaultContainer.removeAllViews()
            val wIp = edtWatchIp.text.toString().trim()
            if (wIp.isNotEmpty() && wIp != "0.0.0.0") {
                ExndWatchTcpBridge.fetchWatchSdWallpapers(wIp) { res ->
                    mainHandler.post {
                        remoteWatchVaultContainer.removeAllViews()
                        res.onSuccess { remoteList ->
                            if (remoteList.isEmpty()) {
                                remoteWatchVaultContainer.addView(TextView(context).apply {
                                    text = "Empty / Vacío"
                                    setTextColor(Color.GRAY)
                                    textSize = 11f
                                })
                            }
                            for (rw in remoteList) {
                                val rRow = LinearLayout(context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
                                    background = GradientDrawable().apply {
                                        setColor(COLOR_CARD)
                                        setStroke(dp(context, 1), COLOR_GREEN)
                                        cornerRadius = dp(context, 6).toFloat()
                                    }
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply { bottomMargin = dp(context, 4) }
                                }
                                val rTxt = TextView(context).apply {
                                    text = "[AW${rw.index}] ${rw.customName}\n${rw.fps} FPS | ${rw.frames} f | ${String.format(Locale.US, "%.2f MB", rw.sizeMb)}"
                                    setTextColor(Color.WHITE)
                                    textSize = 11f
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                }
                                val btnDelSd = Button(context).apply {
                                    text = "X"
                                    setBackgroundColor(COLOR_CRIT)
                                    setTextColor(Color.WHITE)
                                    textSize = 9.5f
                                    setOnClickListener {
                                        ExndWatchTcpBridge.deleteWatchSdWallpaperCascade(wIp, rw.index) {
                                            mainHandler.post { refreshVaults() }
                                        }
                                    }
                                }
                                rRow.addView(rTxt)
                                rRow.addView(btnDelSd)
                                remoteWatchVaultContainer.addView(rRow)
                            }
                        }.onFailure {
                            remoteWatchVaultContainer.addView(TextView(context).apply {
                                text = "Watch offline / Reloj desconectado"
                                setTextColor(Color.GRAY)
                                textSize = 11f
                            })
                        }
                    }
                }
            }
        }

        btnCompileAndSend.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            if (isBusy.get()) return@setOnClickListener
            isBusy.set(true)
            btnCompileAndSend.isEnabled = false

            val chosenFps = fpsValues[spnFps.selectedItemPosition.coerceIn(0, fpsValues.size - 1)]
            val chosenMode = cropView.cropMode
            val normRect = cropView.normalizedCropRect
            val customName = edtCustomName.text.toString().trim().ifBlank { "AW_EXOSYS" }
            val watchIp = edtWatchIp.text.toString().trim()
            val activateImmediate = cbActivateOnWatch.isChecked

            thread(start = true, isDaemon = true) {
                val master = ExndV32Compiler.extractMasterFramesHardware(
                    context = context,
                    uri = uri,
                    idealFps = chosenFps,
                    cropMode = chosenMode,
                    normCrop = normRect
                ) { msg, pct ->
                    mainHandler.post {
                        txtForgeStatus.setTextColor(COLOR_CYAN)
                        txtForgeStatus.text = msg
                        pbForge.progress = (pct * 0.45).toInt()
                    }
                }

                if (master.isEmpty()) {
                    mainHandler.post {
                        isBusy.set(false)
                        btnCompileAndSend.isEnabled = true
                        txtForgeStatus.setTextColor(COLOR_CRIT)
                        txtForgeStatus.text = "[ERROR] Decoder failed / Fallo al decodificar."
                    }
                    return@thread
                }

                val compiled = ExndV32Compiler.compileMasterFramesToExnd(
                    context = context,
                    wallpaperName = customName,
                    masterFrames = master,
                    idealFps = chosenFps
                ) { msg, pct ->
                    mainHandler.post {
                        txtForgeStatus.setTextColor(COLOR_AMBER)
                        txtForgeStatus.text = msg
                        pbForge.progress = 45 + (pct * 0.40).toInt()
                    }
                }

                if (compiled == null) {
                    mainHandler.post {
                        isBusy.set(false)
                        btnCompileAndSend.isEnabled = true
                        txtForgeStatus.setTextColor(COLOR_CRIT)
                        txtForgeStatus.text = "[ENTROPY LIMIT] File > 7.4 MB. Use a shorter clip / Archivo excede 7.4 MB. Usa un video más corto."
                    }
                    return@thread
                }

                if (watchIp.isNotEmpty() && watchIp != "0.0.0.0") {
                    ExndWatchTcpBridge.uploadExndToWatchSd(
                        watchIp = watchIp,
                        exndFile = compiled.file,
                        customName = customName,
                        targetSlot = 0,
                        activateNow = activateImmediate,
                        onProgress = { txPct ->
                            mainHandler.post {
                                pbForge.progress = 85 + (txPct * 15 / 100)
                                txtForgeStatus.setTextColor(COLOR_CYAN)
                                txtForgeStatus.text = "Sending / Enviando ($txPct%)..."
                            }
                        },
                        onResult = { res ->
                            mainHandler.post {
                                isBusy.set(false)
                                btnCompileAndSend.isEnabled = true
                                pbForge.progress = 100
                                refreshVaults()
                                res.onSuccess { reply ->
                                    txtForgeStatus.setTextColor(COLOR_GREEN)
                                    txtForgeStatus.text = "[OK] Saved in SD / Guardado en SD ($reply)."
                                }.onFailure { err ->
                                    txtForgeStatus.setTextColor(COLOR_AMBER)
                                    txtForgeStatus.text = "Compiled but watch offline / Compilado pero reloj sin conexión: ${err.localizedMessage}."
                                }
                            }
                        }
                    )
                } else {
                    mainHandler.post {
                        isBusy.set(false)
                        btnCompileAndSend.isEnabled = true
                        pbForge.progress = 100
                        refreshVaults()
                        txtForgeStatus.setTextColor(COLOR_GREEN)
                        txtForgeStatus.text = "[COMPILED] ${compiled.fps} FPS | ${compiled.profileTag} | ${String.format(Locale.US, "%.2f MB", compiled.sizeBytes / 1048576.0)}."
                    }
                }
            }
        }

        forgeCard.addView(btnPickVideo)
        forgeCard.addView(edtCustomName)
        forgeCard.addView(cropView)
        forgeCard.addView(cropModeRow)
        forgeCard.addView(optsRow)
        forgeCard.addView(cbActivateOnWatch)
        forgeCard.addView(txtForgeStatus)
        forgeCard.addView(pbForge)
        forgeCard.addView(btnCompileAndSend)

        val lblLocalVault = TextView(context).apply {
            text = tLocVault
            setTextColor(COLOR_CYAN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val hdrRemoteRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }
        val lblRemoteVault = TextView(context).apply {
            text = tRemVault
            setTextColor(COLOR_GREEN)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRefreshRemote = Button(context).apply {
            text = tBtnRefresh
            setBackgroundColor(COLOR_DARK)
            setTextColor(COLOR_GREEN)
            textSize = 9.5f
            setOnClickListener { refreshVaults() }
        }
        hdrRemoteRow.addView(lblRemoteVault)
        hdrRemoteRow.addView(btnRefreshRemote)

        val btnClose = Button(context).apply {
            text = tBtnClose
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 10)
            }
            setOnClickListener { dlg?.dismiss() }
        }

        root.addView(topHeaderRow)
        root.addView(ipRow)
        root.addView(forgeCard)
        root.addView(lblLocalVault)
        root.addView(localVaultContainer)
        root.addView(hdrRemoteRow)
        root.addView(remoteWatchVaultContainer)
        root.addView(btnClose)

        refreshVaults()

        dlg = AlertDialog.Builder(context).setView(scroll).setOnDismissListener {
            pendingMediaCallback = null
            firstFrameBmp?.recycle()
            firstFrameBmp = null
        }.create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()
    }
}