// 文件名：VideoPlayer.kt
package com.wangyiheng.vcamsx.utils

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.widget.Toast
import com.wangyiheng.vcamsx.MainHook.Companion.c2_reader_Surfcae
import com.wangyiheng.vcamsx.MainHook.Companion.context
import com.wangyiheng.vcamsx.MainHook.Companion.oriHolder
import com.wangyiheng.vcamsx.MainHook.Companion.original_c1_preview_SurfaceTexture
import com.wangyiheng.vcamsx.MainHook.Companion.original_preview_Surface
import com.wangyiheng.vcamsx.utils.InfoProcesser.videoStatus
import de.robv.android.xposed.XposedBridge // >>>>> 1. 導入 XposedBridge <<<<<
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object VideoPlayer {
    var c2_hw_decode_obj: VideoToFrames? = null
    var ijkMediaPlayer: IjkMediaPlayer? = null
    var mediaPlayer: MediaPlayer? = null
    var c3_player: MediaPlayer? = null
    var copyReaderSurface:Surface? = null
    var currentRunningSurface:Surface? = null
    private val scheduledExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // >>>>> 2. 新增統一的日誌輔助函數 <<<<<
    private fun log(message: String) {
        // 使用一個新的、獨立的標籤，方便過濾
        XposedBridge.log("[VCAMSX_PLAYER_DEBUG] $message")
    }
    
    init {
        log("VideoPlayer object initialized.")
        startTimerTask()
    }

    // 啟動定時任務
    private fun startTimerTask() {
        scheduledExecutor.scheduleWithFixedDelay({
            performTask()
        }, 10, 10, TimeUnit.SECONDS)
    }

    // 實際執行的任務
    private fun performTask() {
        log("Timer task running: Checking for player restart.")
        restartMediaPlayer()
    }

    fun restartMediaPlayer(){
        if(videoStatus?.isVideoEnable == true || videoStatus?.isLiveStreamingEnabled == true) return
        if(currentRunningSurface == null || currentRunningSurface?.isValid == false) return
        log("Restarting media player because it seems idle.")
        releaseMediaPlayer()
    }

    // 公共配置方法
    private fun configureMediaPlayer(player: IjkMediaPlayer) {
        player.apply {
            setOnErrorListener { _, what, extra ->
                // >>>>> 3. 在所有監聽器中加入詳細日誌 <<<<<
                log("!!! IJK Player ERROR. What: $what, Extra: $extra")
                Toast.makeText(context, "播放錯誤: $what", Toast.LENGTH_SHORT).show()
                true
            }
            setOnInfoListener { _, what, extra ->
                log("IJK Player Info. What: $what, Extra: $extra")
                true
            }
        }
    }

    // RTMP流播放器初始化
    fun initRTMPStreamPlayer() {
        log("Initializing RTMP Stream Player...")
        ijkMediaPlayer = IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec_mpeg4", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "analyzemaxduration", 5000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "probesize", 2048L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "flush_packets", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)

            Toast.makeText(context, videoStatus!!.liveURL, Toast.LENGTH_SHORT).show()
            log("RTMP URL: ${videoStatus!!.liveURL}")

            configureMediaPlayer(this)
            dataSource = videoStatus!!.liveURL
            prepareAsync()

            setOnPreparedListener {
                log("IJK Player prepared. Setting surface...")
                original_preview_Surface?.let { setSurface(it) }
                Toast.makeText(context, "直播接收成功", Toast.LENGTH_SHORT).show()
                log("Starting IJK Player...")
                start()
            }
        }
    }

    fun initMediaPlayer(surface:Surface){
        log("Initializing local MediaPlayer for surface: $surface")
        val volume = if (videoStatus?.volume == true) 1F else 0F
        mediaPlayer = MediaPlayer().apply {
            try {
                isLooping = true
                setSurface(surface)
                setVolume(volume,volume)
                setOnPreparedListener {
                    log("Local MediaPlayer prepared. Starting playback.")
                    start()
                }
                // >>>>> 3. 在所有監聽器中加入詳細日誌 <<<<<
                setOnErrorListener { _, what, extra ->
                    log("!!! Local MediaPlayer ERROR. What: $what, Extra: $extra")
                    true
                }
                val videoPathUri = Uri.parse("content://com.wangyiheng.vcamsx.videoprovider")
                log("Setting data source: $videoPathUri")
                context?.let { ctx -> setDataSource(ctx, videoPathUri) }
                prepareAsync() // 使用異步準備，避免阻塞
            } catch (e: Exception) {
                log("!!! FATAL ERROR in initMediaPlayer: ${e.message}")
                XposedBridge.log(e)
            }
        }
    }

    fun initializeTheStateAsWellAsThePlayer(){
        log("Initializing state and player...")
        InfoProcesser.initStatus()
        log("Status loaded: isLiveStreamingEnabled=${videoStatus?.isLiveStreamingEnabled}")

        if(ijkMediaPlayer == null){
            if(videoStatus?.isLiveStreamingEnabled == true){
                initRTMPStreamPlayer()
            }
        }
    }


    private fun handleMediaPlayer(surface: Surface) {
        // >>>>> 4. 為核心方法添加詳細的進入和狀態檢查日誌 <<<<<
        log("handleMediaPlayer called for surface: $surface")
        if (!surface.isValid) {
            log("!!! ERROR: Surface is invalid, aborting playback.")
            return
        }

        try {
            InfoProcesser.initStatus()
            log("Video status checked: isVideoEnable=${videoStatus?.isVideoEnable}, isLiveStreamingEnabled=${videoStatus?.isLiveStreamingEnabled}")

            videoStatus?.also { status ->
                if (!status.isVideoEnable && !status.isLiveStreamingEnabled) {
                    log("Both video and live streaming are disabled. Returning.")
                    releaseMediaPlayer()
                    return
                }

                currentRunningSurface = surface
                val volume = if (status.volume) 1F else 0F

                when {
                    status.isLiveStreamingEnabled -> {
                        log("Handling live streaming playback.")
                        ijkMediaPlayer?.let {
                            log("IJK player exists. Setting volume and surface.")
                            it.setVolume(volume, volume)
                            it.setSurface(surface)
                        } ?: run {
                            log("IJK player is null. Re-initializing RTMP player.")
                            initRTMPStreamPlayer()
                            ijkMediaPlayer?.setSurface(surface)
                        }
                    }
                    else -> {
                        log("Handling local video playback.")
                        mediaPlayer?.also {
                            if (it.isPlaying) {
                                log("Local player exists and is playing. Setting volume and surface.")
                                it.setVolume(volume, volume)
                                it.setSurface(surface)
                            } else {
                                log("Local player exists but is not playing. Re-initializing.")
                                releaseMediaPlayer()
                                initMediaPlayer(surface)
                            }
                        } ?: run {
                            log("Local player is null. Initializing now.")
                            releaseMediaPlayer()
                            initMediaPlayer(surface)
                        }
                    }
                }
            } ?: log("Warning: videoStatus is null in handleMediaPlayer.")
        } catch (e: Exception) {
            log("!!! FATAL ERROR in handleMediaPlayer: ${e.message}")
            XposedBridge.log(e)
        }
    }

    private fun logError(message: String, e: Exception) {
        log("$message: ${e.message}")
        XposedBridge.log(e)
    }

    fun releaseMediaPlayer(){
        log("Releasing MediaPlayer...")
        if(mediaPlayer == null) {
            log("MediaPlayer is already null. No action taken.")
            return
        }
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            log("Error while releasing MediaPlayer: ${e.message}")
        } finally {
            mediaPlayer = null
            log("MediaPlayer set to null.")
        }
    }

    fun camera2Play() {
        // >>>>> 4. 為核心方法添加詳細的進入和狀態檢查日誌 <<<<<
        log("camera2Play() triggered!")
        original_preview_Surface?.let { surface ->
            log("Found original_preview_Surface ($surface), passing to handleMediaPlayer.")
            handleMediaPlayer(surface)
        } ?: log("Warning: original_preview_Surface is null in camera2Play.")

        c2_reader_Surfcae?.let { surface ->
            log("Found c2_reader_Surfcae ($surface), passing to c2_reader_play.")
            c2_reader_play(surface)
        }
    }

    fun c1_camera_play() {
        // >>>>> 4. 為核心方法添加詳細的進入和狀態檢查日誌 <<<<<
        log("c1_camera_play() triggered!")
        if (original_c1_preview_SurfaceTexture != null) {
            original_preview_Surface = Surface(original_c1_preview_SurfaceTexture)
            if(original_preview_Surface?.isValid == true){
                log("Created Surface from original_c1_preview_SurfaceTexture, passing to handleMediaPlayer.")
                handleMediaPlayer(original_preview_Surface!!)
            } else {
                log("Warning: Surface from original_c1_preview_SurfaceTexture is invalid.")
            }
        }

        if(oriHolder?.surface != null){
            original_preview_Surface = oriHolder?.surface
            if(original_preview_Surface?.isValid == true){
                log("Got Surface from oriHolder, passing to handleMediaPlayer.")
                handleMediaPlayer(original_preview_Surface!!)
            } else {
                log("Warning: Surface from oriHolder is invalid.")
            }
        }
    }

   fun c2_reader_play(c2_reader_Surfcae:Surface){
        log("c2_reader_play called for surface: $c2_reader_Surfcae")
        if(c2_reader_Surfcae == copyReaderSurface){
            log("Surface is the same as last time, returning.")
            return
        }
        copyReaderSurface = c2_reader_Surfcae
        if(c2_hw_decode_obj != null){
            log("Stopping previous c2_hw_decode_obj.")
            c2_hw_decode_obj!!.stopDecode()
            c2_hw_decode_obj = null
        }
        c2_hw_decode_obj = VideoToFrames()
        try {
            // >>>>> 【最終釜底抽薪修正】START <<<<<

            // 1. 不再依賴 InfoManager，直接定義我們約定好的全局文件路徑
            val videoPath = "/data/local/tmp/vcamsx_video/playing_video.mp4"
            val videoFile = java.io.File(videoPath)

            // 2. 檢查這個約定好的文件是否存在
            if (!videoFile.exists()) {
                val errorMessage = "!!! FATAL ERROR: Video file does not exist at the global path: $videoPath"
                log(errorMessage)
                
                // 【線程安全 Toast】如果要顯示 Toast，必須切換到主線程
                if (context != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "VCAMSX 錯誤: 未找到影片文件，請先在主程式中選擇！", Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
            
            // 3. 如果文件存在，直接使用這個路徑進行解碼
            log("Found video file at global path. Starting decoding: $videoPath")
            
            c2_hw_decode_obj!!.setSaveFrames(OutputImageFormat.NV21)
            c2_hw_decode_obj!!.set_surface(c2_reader_Surfcae)
            c2_hw_decode_obj!!.decode(videoPath)
            
            // >>>>> 【最終釜底抽薪修正】END <<<<<

        }catch (e:Exception){
            log("!!! ERROR in c2_reader_play: ${e.message}")
            XposedBridge.log(e)
        }
    }

}
