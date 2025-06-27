package com.wangyiheng.vcamsx.utils

import android.content.ContentValues
import android.content.ContentValues.TAG
import android.graphics.*
import android.media.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.wangyiheng.vcamsx.MainHook
import com.wangyiheng.vcamsx.MainHook.Companion.context
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class VideoToFrames : Runnable {

    private var stopDecode = false

    private var outputImageFormat: OutputImageFormat? = null
    private var videoFilePath: Any? = null
    private var childThread: Thread? = null
    private var throwable: Throwable? = null
    private val decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var play_surf: Surface? = null
    private val DEFAULT_TIMEOUT_US: Long = 10000
    private val callback: Callback? = null
    private val mQueue: LinkedBlockingQueue<ByteArray>? = null
    private val COLOR_FormatI420 = 1
    private val COLOR_FormatNV21 = 2
    private val VERBOSE = false

    // >>>>> 1. 新增統一的日誌輔助函數 <<<<<
    private fun log(message: String) {
        XposedBridge.log("[VCAMSX_DECODER_DEBUG] $message")
    }
    private fun logError(message: String, t: Throwable? = null) {
        XposedBridge.log("[VCAMSX_DECODER_DEBUG] !!! ERROR: $message")
        t?.let { XposedBridge.log(it) }
    }

    fun stopDecode() {
        log("stopDecode() called. Decoding will stop soon.")
        stopDecode = true
    }

    interface Callback {
        fun onFinishDecode()
        fun onDecodeFrame(index: Int)
    }

    @Throws(IOException::class)
    fun setSaveFrames(imageFormat: OutputImageFormat) {
        log("Setting save format to: $imageFormat")
        outputImageFormat = imageFormat
    }

    fun set_surface(player_surface:Surface){
        log("Setting output surface: $player_surface")
        if(player_surface.isValid) {
            log("Surface is valid.")
            play_surf = player_surface
        } else {
            logError("Provided surface is NOT valid!")
        }
    }

    fun decode(videoFilePath: Any) {
        log("decode() called for path: $videoFilePath")
        this.videoFilePath = videoFilePath
        if (childThread == null) {
            childThread = Thread(this, "VideoDecodeThread").apply {
                start()
            }
            throwable?.let {
                logError("An error occurred during thread creation.", it)
                throw it
            }
        } else {
            log("Warning: decode() called but thread already exists.")
        }
    }

    override fun run() {
        // >>>>> 2. 為 run 方法添加日誌 <<<<<
        log("Decoding thread started. Let's begin...")
        try {
            videoFilePath?.let { videoDecode(it) } ?: logError("videoFilePath is null in run().")
        } catch (t: Throwable) {
            logError("Unhandled throwable in run()", t)
            throwable = t
        } finally {
            log("Decoding thread finished.")
        }
    }

    private fun videoDecode(videoPath: Any) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        log("videoDecode started.")
        try {
            extractor = MediaExtractor().apply {
                when (videoPath) {
                    is String -> {
                        log("Setting data source from String path.")
                        setDataSource(videoPath)
                    }
                    is Uri -> {
                        log("Setting data source from Uri.")
                        context?.let { ctx -> setDataSource(ctx, videoPath, null) }
                    }
                    else -> throw IllegalArgumentException("Unsupported video path type")
                }
            }
            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                throw RuntimeException("No video track found in $videoFilePath")
            }
            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            // >>>>> 3. 打印詳細的影片格式資訊 <<<<<
            log("Video format found: ${mediaFormat.toString()}")

            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
            log("Video MIME type: $mime")
            decoder = MediaCodec.createDecoderByType(mime!!)
            log("MediaCodec decoder created for $mime.")

            showSupportedColorFormat(decoder.codecInfo.getCapabilitiesForType(mime))
            if (isColorFormatSupported(decodeColorFormat, decoder.codecInfo.getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat)
                log("Successfully set decode color format to: $decodeColorFormat (YUV420Flexible)")
            } else {
                log("Warning: YUV420Flexible not supported, using default color format.")
            }

            // Loop a few times for testing, for example 5 times.
            var loopCount = 0
            while (!stopDecode && loopCount < 500) { // 加一個循環次數限制，避免無限循環
                log("Starting decoding loop #${loopCount + 1}")
                decodeFramesToImage(decoder, extractor, mediaFormat)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                log("Seeked to beginning for next loop.")
                loopCount++
            }
            log("Decoding loops finished.")

        } catch (e: Exception) {
            logError("Exception in videoDecode", e)
        } finally {
            log("Releasing videoDecode resources...")
            decoder?.stop()
            decoder?.release()
            extractor?.release()
            log("videoDecode resources released.")
        }
    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        log("Found $numTracks tracks.")
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            log("Track #$i: $mime")
            if (mime!!.startsWith("video/")) {
                log("Video track selected at index $i.")
                return i
            }
        }
        return -1
    }

    private fun showSupportedColorFormat(caps: MediaCodecInfo.CodecCapabilities) {
        val supportedFormats = caps.colorFormats.joinToString(", ")
        log("Supported color formats: [$supportedFormats]")
    }

    private fun isColorFormatSupported(colorFormat: Int, caps: MediaCodecInfo.CodecCapabilities): Boolean {
        return caps.colorFormats.any { it == colorFormat }
    }

    private fun decodeFramesToImage(decoder: MediaCodec, extractor: MediaExtractor, mediaFormat: MediaFormat) {
        var isFirst = false
        var startWhen: Long = 0
        val info = MediaCodec.BufferInfo()
        log("Configuring decoder with format and surface ($play_surf)...")
        decoder.configure(mediaFormat, play_surf, null, 0)
        decoder.start()
        log("Decoder started.")
        var sawInputEOS = false
        var sawOutputEOS = false
        var outputFrameCount = 0

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                try {
                    val inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferId)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            log("Input stream ended. Queuing EOS.")
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                } catch (e: Exception) {
                    logError("Error during input processing", e)
                    sawInputEOS = true // Abort on error
                }
            }

            val outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US)
            if (outputBufferId >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    log("Output stream ended.")
                    sawOutputEOS = true
                }
                val doRender = info.size != 0
                // >>>>> 4. 在渲染循環中加入詳細日誌 <<<<<
                log("Frame #$outputFrameCount: Dequeued output buffer $outputBufferId. Size: ${info.size}, Render: $doRender")

                if (doRender) {
                    outputFrameCount++

                    if (!isFirst) {
                        startWhen = System.currentTimeMillis()
                        isFirst = true
                    }

                    // 檢查是否有預覽 surface，如果有，數據會被直接渲染上去
                    if (play_surf != null) {
                        log("Frame #$outputFrameCount is being rendered to surface.")
                    } else {
                        // 如果沒有 surface，我們手動處理數據幀
                        log("Frame #$outputFrameCount is being processed manually (no surface).")
                        val image = decoder.getOutputImage(outputBufferId)
                        if (image != null) {
                            logImageFormat(image) // 打印圖像格式
                            if (outputImageFormat != null) {
                                log("Converting image to byte array (for data_buffer).")
                                MainHook.data_buffer = getDataFromImage(image)
                                log("data_buffer updated. Size: ${MainHook.data_buffer.size}")
                            }
                            image.close()
                        } else {
                            log("Warning: decoder.getOutputImage($outputBufferId) returned null.")
                        }
                    }
                    
                    // 根據時間戳計算延遲
                    val sleepTime = (info.presentationTimeUs / 1000) - (System.currentTimeMillis() - startWhen)
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime)
                        } catch (e: InterruptedException) {
                            log("Thread sleep interrupted.")
                        }
                    }

                    // 釋放緩衝區，如果是渲染到 surface，這一步會將畫面顯示出來
                    decoder.releaseOutputBuffer(outputBufferId, true)
                    log("Frame #$outputFrameCount released to render.")
                } else {
                    // 如果 doRender 為 false，也要釋放 buffer
                    decoder.releaseOutputBuffer(outputBufferId, false)
                }
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                log("Output format changed: ${decoder.outputFormat}")
            }
        }
        decoder.stop()
        log("Decoder stopped for this loop.")
        callback?.onFinishDecode()
    }


    fun logImageFormat(image: Image) {
        val format = image.format
        val formatString = when (format) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.NV21 -> "NV21"
            ImageFormat.YV12 -> "YV12"
            ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.RAW12 -> "RAW12"
            ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
            ImageFormat.DEPTH16 -> "DEPTH16"
            ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
            // 添加更多格式根据需要
            else -> "Unknown format: $format"
        }
        Log.d("vcamsx", "Image format is $formatString")
    }

    fun imageToBitmap(image: Image): Bitmap {
        Log.d("vcamsx",image.format.toString())
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // YUV_420_888数据转NV21
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 75, out)

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

//    fun bitmapToByteArray(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
//        val stream = ByteArrayOutputStream()
//        bitmap.compress(format, quality, stream)
//        return stream.toByteArray()
//    }

    fun bitmapToYUV(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val intArray = IntArray(width * height)
        bitmap.getPixels(intArray, 0, width, 0, 0, width, height)

        val yuvArray = ByteArray(width * height * 3)

        var index = 0
        intArray.forEach { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // Apply the RGB to YUV formula
            val y = (0.257 * r) + (0.504 * g) + (0.098 * b) + 16
            val u = -(0.148 * r) - (0.291 * g) + (0.439 * b) + 128
            val v = (0.439 * r) - (0.368 * g) - (0.071 * b) + 128

            // Assuming the YUV format is YUV444, store each Y, U, and V value sequentially
            yuvArray[index++] = y.toInt().toByte()
            yuvArray[index++] = u.toInt().toByte()
            yuvArray[index++] = v.toInt().toByte()
        }

        return yuvArray
    }
//    private fun getDataFromImage(image: Image, colorFormat: Int): ByteArray {
//        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21) {
//            throw IllegalArgumentException("only support COLOR_FormatI420 and COLOR_FormatNV21")
//        }
//
//        logImageFormat(image)
//        if (!isImageFormatSupported(image)) {
//            throw RuntimeException("can't convert Image to byte array, format ${image.format}")
//        }
//
//
//        val crop = image.cropRect
//        val format = image.format
//        val width = crop.width()
//        val height = crop.height()
//        val planes = image.planes
//        val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(format) / 8)
//        val rowData = ByteArray(planes[0].rowStride)
//
//        var channelOffset = 0
//        var outputStride = 1
//        for (i in planes.indices) {
//            when (i) {
//                0 -> {
//                    channelOffset = 0
//                    outputStride = 1
//                }
//                1 -> {
//                    channelOffset = if (colorFormat == COLOR_FormatI420) width * height else width * height + 1
//                    outputStride = 2
//                }
//                2 -> {
//                    channelOffset = if (colorFormat == COLOR_FormatI420) (width * height * 1.25).toInt() else width * height
//                    outputStride = 2
//                }
//            }
//            val buffer = planes[i].buffer
//            val rowStride = planes[i].rowStride
//            val pixelStride = planes[i].pixelStride
//
//            val shift = if (i == 0) 0 else 1
//            val w = width shr shift
//            val h = height shr shift
//            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))
//            for (row in 0 until h) {
//                val length: Int
//                if (pixelStride == 1 && outputStride == 1) {
//                    length = w
//                    buffer.get(data, channelOffset, length)
//                    channelOffset += length
//                } else {
//                    length = (w - 1) * pixelStride + 1
//                    buffer.get(rowData, 0, length)
//                    for (col in 0 until w) {
//                        data[channelOffset] = rowData[col * pixelStride]
//                        channelOffset += outputStride
//                    }
//                }
//                if (row < h - 1) {
//                    buffer.position(buffer.position() + rowStride - length)
//                }
//            }
//        }
//        return data
//    }

    private fun getDataFromImage(image: Image): ByteArray {

        logImageFormat(image)
        if (!isImageFormatSupported(image)) {
            throw RuntimeException("can't convert Image to byte array, format ${image.format}")
        }

        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes
        val pixelFormatBits = ImageFormat.getBitsPerPixel(image.format)
        val data = ByteArray(width * height * pixelFormatBits / 8)
        val rowData = ByteArray(planes[0].rowStride)

        fun copyPlaneData(planeIndex: Int, buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, channelOffset: Int, outputStride: Int) {
            var outputOffset = channelOffset
            buffer.position(rowStride * (crop.top / 2) + pixelStride * (crop.left / 2))
            for (row in 0 until height) {
                val length = if (pixelStride == 1 && outputStride == 1) {
                    width
                } else {
                    (width - 1) * pixelStride + 1
                }
                if (length == rowStride && outputStride == 1) {
                    buffer.get(data, outputOffset, length)
                    outputOffset += length
                } else {
                    buffer.get(rowData, 0, length)
                    for (col in 0 until width) {
                        data[outputOffset] = rowData[col * pixelStride]
                        outputOffset += outputStride
                    }
                }
                if (row < height - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }

        var channelOffset = 0
        val uvHeight = height / 2
        val uvWidth = width / 2

        // Y Plane
        copyPlaneData(0, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, width, height, channelOffset, 1)
        channelOffset += width * height


        copyPlaneData(1, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, uvWidth, uvHeight, channelOffset, 2)
        copyPlaneData(2, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, uvWidth, uvHeight, channelOffset + 1, 2)


        return data
    }



    private fun isImageFormatSupported(image: Image): Boolean {
        val format = image.format
        Log.d("vcamsx", "format$format")
        return when (format) {
            ImageFormat.YUV_420_888, ImageFormat.NV21, ImageFormat.YV12 -> true
            else -> false
        }
    }
}


enum class OutputImageFormat(val friendlyName: String) {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");

    override fun toString() = friendlyName
}

