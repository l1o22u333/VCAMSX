package com.wangyiheng.vcamsx.modules.home.controllers

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.wangyiheng.vcamsx.MainHook
import com.wangyiheng.vcamsx.data.models.UploadIpRequest
import com.wangyiheng.vcamsx.data.models.VideoInfo
import com.wangyiheng.vcamsx.data.models.VideoStatues
import com.wangyiheng.vcamsx.data.services.ApiService
import com.wangyiheng.vcamsx.utils.InfoManager
import com.wangyiheng.vcamsx.utils.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File
import java.io.IOException
import java.net.URL
import java.io.FileOutputStream
import com.wangyiheng.vcamsx.utils.FileUtils

class HomeController: ViewModel(),KoinComponent {
    val apiService: ApiService by inject()
    val context by inject<Context>()
    val isVideoEnabled  = mutableStateOf(false)
    val isVolumeEnabled = mutableStateOf(false)
    val videoPlayer = mutableStateOf(1)
    val codecType = mutableStateOf(false)
    val isLiveStreamingEnabled = mutableStateOf(false)

    val infoManager by inject<InfoManager>()
    var ijkMediaPlayer: IjkMediaPlayer? = null
    var mediaPlayer:MediaPlayer? = null
    val isLiveStreamingDisplay =  mutableStateOf(false)
    val isVideoDisplay =  mutableStateOf(false)
//    rtmp://ns8.indexforce.com/home/mystream
    var liveURL = mutableStateOf("rtmp://ns8.indexforce.com/home/mystream")

    fun init(){
        getState()
        saveImage()
    }
    suspend fun getPublicIpAddress(): String? = withContext(Dispatchers.IO) {
        try {
            URL("https://api.ipify.org").readText()
        } catch (ex: Exception) {
            null
        }
    }


    fun saveImage() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ipAddress = getPublicIpAddress()
                if (ipAddress != null) {
                    apiService.uploadIp(UploadIpRequest(ipAddress))
                }
            } catch (e: Exception) {
                Log.d("错误", "${e.message}")
            }
        }
    }


    fun copyVideoToAppDir(context: Context, videoUri: Uri) {
        // >>>>> 【最終修正 V4】START：採用流複製 + Root cp 的終極方案 <<<<<
        val targetFilePath = "/data/local/tmp/vcamsx_video/playing_video.mp4"
        // 在 App 自己的緩存目錄下創建一個臨時文件
        val tempFile = File(context.cacheDir, "temp_video.mp4")

        try {
            // 步驟 1: 將用戶選擇的影片內容，通過流的方式，先複製到我們 App 自己的緩存臨時文件中
            context.contentResolver.openInputStream(videoUri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d("VCAMSX_HOME", "Video content copied to temporary file: ${tempFile.absolutePath}")

            // 步驟 2: 現在我們有了一個路徑絕對可靠的臨時文件，讓 Root 去複製它
            val sourcePath = tempFile.absolutePath
            val command = "cp '$sourcePath' '$targetFilePath' && " +
                          "chmod 666 '$targetFilePath' && " +
                          "chcon u:object_r:app_data_file:s0 '$targetFilePath'"

            Log.d("VCAMSX_HOME", "Executing root command: $command")
            
            // 步驟 3: 執行 su 命令
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            
            // 步驟 4: 檢查命令是否執行成功
            if (exitCode == 0) {
                Log.d("VCAMSX_HOME", "Video copied to global path successfully with root.")
                Toast.makeText(context, "影片已選擇並複製成功！", Toast.LENGTH_SHORT).show()

                infoManager.removeVideoInfo()
                infoManager.saveVideoInfo(VideoInfo(videoUrl = targetFilePath))
            } else {
                Log.e("VCAMSX_HOME", "Root command failed with exit code: $exitCode")
                val error = process.errorStream.bufferedReader().readText()
                Log.e("VCAMSX_HOME", "Root command error: $error")
                Toast.makeText(context, "Root 命令執行失敗: $error", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Log.e("VCAMSX_HOME", "Failed to copy video to global path", e)
            Toast.makeText(context, "選擇影片失敗: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            // 步驟 5: 無論成功與否，都刪掉臨時文件
            if (tempFile.exists()) {
                tempFile.delete()
                Log.d("VCAMSX_HOME", "Temporary file deleted.")
            }
        }
        // >>>>> 【最終修正 V4】END <<<<<
    }
    fun saveState() {
        infoManager.removeVideoStatus()
        infoManager.saveVideoStatus(
            VideoStatues(
                isVideoEnabled.value,
                isVolumeEnabled.value,
                videoPlayer.value,
                codecType.value,
                isLiveStreamingEnabled.value,
                liveURL.value
            )
        )
    }

    fun getState(){
        infoManager.getVideoStatus()?.let {
            isVideoEnabled.value = it.isVideoEnable
            isVolumeEnabled.value = it.volume
            videoPlayer.value = it.videoPlayer
            codecType.value = it.codecType
            isLiveStreamingEnabled.value = it.isLiveStreamingEnabled
            liveURL.value = it.liveURL
        }
    }


    fun playVideo(holder: SurfaceHolder) {
        val videoUrl = "content://com.wangyiheng.vcamsx.videoprovider"
        val videoPathUri = Uri.parse(videoUrl)

        mediaPlayer = MediaPlayer().apply {
            try {
                isLooping = true
                setSurface(holder.surface) // 使用SurfaceHolder的surface
                setDataSource(context, videoPathUri) // 设置数据源
                prepareAsync() // 异步准备MediaPlayer

                // 设置准备监听器
                setOnPreparedListener {
                    start() // 准备完成后开始播放
                }

                // 可选：设置错误监听器
                setOnErrorListener { mp, what, extra ->
                    // 处理播放错误
                    true
                }
            } catch (e: IOException) {
                e.printStackTrace()
                // 处理设置数据源或其他操作时的异常
            }
        }
    }


    fun playRTMPStream(holder: SurfaceHolder, rtmpUrl: String) {
        ijkMediaPlayer = IjkMediaPlayer().apply {
            try {
                // 硬件解码设置,0为软解，1为硬解
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)

                // 缓冲设置
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec_mpeg4", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "analyzemaxduration", 100L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "probesize", 1024L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "flush_packets", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)

                // 错误监听器
                setOnErrorListener { _, what, extra ->
                    Log.e("IjkMediaPlayer", "Error occurred. What: $what, Extra: $extra")
                    Toast.makeText(context, "直播接收失败$what", Toast.LENGTH_SHORT).show()
                    true
                }

                // 信息监听器
                setOnInfoListener { _, what, extra ->
                    Log.i("IjkMediaPlayer", "Info received. What: $what, Extra: $extra")
                    true
                }

                // 设置 RTMP 流的 URL
                dataSource = rtmpUrl

                // 设置视频输出的 SurfaceHolder
                setDisplay(holder)

                // 异步准备播放器
                prepareAsync()

                // 当播放器准备好后，开始播放
                setOnPreparedListener {
                    Toast.makeText(context, "直播接收成功，可以进行投屏", Toast.LENGTH_SHORT).show()
                    start()
                }
            } catch (e: Exception) {
                Log.d("vcamsx","播放报错$e")
            }
        }
    }

    fun release(){
        ijkMediaPlayer?.stop()
        ijkMediaPlayer?.release()
        ijkMediaPlayer = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun isH264HardwareDecoderSupport(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfos = codecList.codecInfos
        for (codecInfo in codecInfos) {
            if (!codecInfo.isEncoder && codecInfo.name.contains("avc") && !isSoftwareCodec(codecInfo.name)) {
                return true
            }
        }
        return false
    }

    fun isSoftwareCodec(codecName: String): Boolean {
        return when {
            codecName.startsWith("OMX.google.") -> true
            codecName.startsWith("OMX.") -> false
            else -> true
        }
    }
}

