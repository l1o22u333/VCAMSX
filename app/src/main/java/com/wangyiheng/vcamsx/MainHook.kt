// 文件名：MainHook.kt
package com.wangyiheng.vcamsx
import android.app.Application
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.utils.InfoProcesser.videoStatus
import com.wangyiheng.vcamsx.utils.OutputImageFormat
import com.wangyiheng.vcamsx.utils.VideoPlayer.c1_camera_play
import com.wangyiheng.vcamsx.utils.VideoPlayer.ijkMediaPlayer
import com.wangyiheng.vcamsx.utils.VideoPlayer.camera2Play
import com.wangyiheng.vcamsx.utils.VideoPlayer.initializeTheStateAsWellAsThePlayer
import com.wangyiheng.vcamsx.utils.VideoToFrames
import de.robv.android.xposed.*
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.min
import com.wangyiheng.vcamsx.utils.DebugOverlay // <<<<<<<<<<<<<<<< 1. 確保導入 DebugOverlay

class MainHook : IXposedHookLoadPackage {
    companion object {
        val TAG = "vcamsx"
        @Volatile
        var data_buffer = byteArrayOf(0)
        var context: Context? = null
        var origin_preview_camera: Camera? = null
        var fake_SurfaceTexture: SurfaceTexture? = null
        var c1FakeTexture: SurfaceTexture? = null
        var c1FakeSurface: Surface? = null

        var sessionConfiguration: SessionConfiguration? = null
        var outputConfiguration: OutputConfiguration? = null
        var fake_sessionConfiguration: SessionConfiguration? = null

        var original_preview_Surface: Surface? = null
        var original_c1_preview_SurfaceTexture:SurfaceTexture? = null
        var isPlaying:Boolean = false
        var needRecreate: Boolean = false
        var c2VirtualSurfaceTexture: SurfaceTexture? = null
        var c2_reader_Surfcae: Surface? = null
        var camera_onPreviewFrame: Camera? = null
        var camera_callback_calss: Class<*>? = null
        var hw_decode_obj: VideoToFrames? = null

        var mcamera1: Camera? = null
        var oriHolder: SurfaceHolder? = null


        // >>>>> 新增/修改 START <<<<<
        /**
         * 統一的調試日誌記錄器
         * @param message 簡潔的資訊，用於顯示在懸浮窗和日誌中
         * @param details 詳細的資訊，只會記錄到Xposed Log
         * @param toOverlay 是否將此信息更新到懸浮窗上
         * @param lpparam 可選的LoadPackageParam，用於打印進程名
         */
        private fun logDebug(message: String, details: String? = null, toOverlay: Boolean = false, lpparam: XC_LoadPackage.LoadPackageParam?) {
            val logMessage = buildString {
                append("[VCAMSX_DEBUG] ")
                if (lpparam != null) append("[${lpparam.processName}] ")
                append(message)
                if (details != null) append("\n\t> $details")
            }

            // 1. 無論如何，都記錄到 XposedBridge Log
            XposedBridge.log(logMessage)

            // 2. 如果標記為 toOverlay，則更新到懸浮窗
            if (toOverlay) {
                context?.let { DebugOverlay.show(it, message) }
            }
        }

        /**
         * 統一的錯誤記錄器
         * @param t 捕獲到的異常
         * @param fromFunction 發生錯誤的函數名
         * @param lpparam 可選的LoadPackageParam
         */
        private fun logError(t: Throwable, fromFunction: String, lpparam: XC_LoadPackage.LoadPackageParam?) {
            val errorMessage = "!!! ERROR in $fromFunction: ${t.javaClass.simpleName}"

            val logMessage = buildString {
                append("[VCAMSX_DEBUG] ")
                if (lpparam != null) append("[${lpparam.processName}] ")
                append(errorMessage)
            }

            // 1. 在懸浮窗上醒目地顯示錯誤
            context?.let { DebugOverlay.show(it, errorMessage) }

            // 2. 在 Xposed Log 中記錄完整的錯誤堆棧
            XposedBridge.log(logMessage)
            XposedBridge.log(t)
        }
        // >>>>> 新增/修改 END <<<<<
    }

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null

    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // >>>>> 新增/修改 START <<<<<
        logDebug("Module loading", "Package: ${lpparam.packageName}", false, lpparam)
        // >>>>> 新增/修改 END <<<<<

        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }
//        if(lpparam.processName.contains(":")) {
//            Log.d(TAG,"当前进程："+lpparam.processName)
//            return
//        }

        //獲取context
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    // >>>>> 新增/修改 START <<<<<
                    logDebug("Hooked: Instrumentation.callApplicationOnCreate", null, false, lpparam)
                    try {
                    // >>>>> 新增/修改 END <<<<<
                        param?.args?.firstOrNull()?.let { arg ->
                            if (arg is Application) {
                                val applicationContext = arg.applicationContext
                                if (context != applicationContext) {
                                    context = applicationContext
                                    // >>>>> 新增/修改 START <<<<<
                                    logDebug("Context obtained!", "Package: ${context?.packageName}", true, lpparam)
                                    // >>>>> 新增/修改 END <<<<<

                                    if (!isPlaying) {
                                        isPlaying = true
                                        ijkMediaPlayer ?: initializeTheStateAsWellAsThePlayer()
                                        // >>>>> 新增/修改 START <<<<<
                                        logDebug("Player Initialized", null, false, lpparam)
                                        // >>>>> 新增/修改 END <<<<<
                                    }
                                }
                            }
                        }
                    // >>>>> 新增/修改 START <<<<<
                    } catch (t: Throwable) {
                        logError(t, "callApplicationOnCreate", lpparam)
                    }
                    // >>>>> 新增/修改 END <<<<<
                }
            }
        )

        // 支持bilibili摄像头替换
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // >>>>> 新增/修改 START <<<<<
                    logDebug("Hooked: C1.setPreviewTexture", "SurfaceTexture: ${param.args[0]}", true, lpparam)
                    try {
                    // >>>>> 新增/修改 END <<<<<
                        if (param.args[0] == null) {
                            return
                        }
                        if (param.args[0] == fake_SurfaceTexture) {
                            return
                        }
                        if (origin_preview_camera != null && origin_preview_camera == param.thisObject) {
                            param.args[0] = fake_SurfaceTexture
                            logDebug("Reusing fake_SurfaceTexture", null, false, lpparam)
                            return
                        }

                        origin_preview_camera = param.thisObject as Camera
                        original_c1_preview_SurfaceTexture = param.args[0] as SurfaceTexture
                        logDebug("Original C1 SurfaceTexture saved", "$original_c1_preview_SurfaceTexture", false, lpparam)

                        fake_SurfaceTexture = if (fake_SurfaceTexture == null) {
                            SurfaceTexture(10)
                        } else {
                            fake_SurfaceTexture!!.release()
                            SurfaceTexture(10)
                        }
                        param.args[0] = fake_SurfaceTexture
                        logDebug("Argument replaced with fake SurfaceTexture", null, false, lpparam)
                    // >>>>> 新增/修改 START <<<<<
                    } catch (t: Throwable) {
                        logError(t, "setPreviewTexture", lpparam)
                    }
                    // >>>>> 新增/修改 END <<<<<
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                // >>>>> 新增/修改 START <<<<<
                logDebug("Hooked: C1.startPreview", "Triggering c1_camera_play()", true, lpparam)
                try {
                // >>>>> 新增/修改 END <<<<<
                    c1_camera_play()
                // >>>>> 新增/修改 START <<<<<
                } catch (t: Throwable) {
                    logError(t, "startPreview", lpparam)
                }
                // >>>>> 新增/修改 END <<<<<
            }
        })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // >>>>> 新增/修改 START <<<<<
                    logDebug("Hooked: C1.setPreviewCallbackWithBuffer", "Callback: ${param.args[0]}", true, lpparam)
                    try {
                    // >>>>> 新增/修改 END <<<<<
                        if(videoStatus?.isVideoEnable == false) return
                        if (param.args[0] != null) {
                            process_callback(param)
                        }
                    // >>>>> 新增/修改 START <<<<<
                    } catch (t: Throwable) {
                        logError(t, "setPreviewCallbackWithBuffer", lpparam)
                    }
                    // >>>>> 新增/修改 END <<<<<
                }
            })
        // >>>>> 新增/修改 START (addCallbackBuffer) <<<<<

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
            ByteArray::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 這個方法可能會被頻繁調用，所以預設不在懸浮窗上顯示，避免刷屏
                    logDebug("Hooked: C1.addCallbackBuffer", "Original Buffer Size: ${(param.args[0] as? ByteArray)?.size}", false, lpparam)
                    try {
                        if (param.args[0] != null) {
                            // 替換為一個新的、乾淨的緩衝區，以避免原始相機數據污染
                            param.args[0] = ByteArray((param.args[0] as ByteArray).size)
                            logDebug("Replaced callback buffer", null, false, lpparam)
                        }
                    } catch (t: Throwable) {
                        logError(t, "addCallbackBuffer", lpparam)
                    }
                }
            })
            
        // >>>>> 新增/修改 END (addCallbackBuffer) <<<<<

        // >>>>> 新增/修改 START (setPreviewDisplay) <<<<<

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay",
            SurfaceHolder::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                logDebug("Hooked: C1.setPreviewDisplay", "SurfaceHolder: ${param.args[0]}", true, lpparam)
                try {
                    mcamera1 = param.thisObject as Camera
                    oriHolder = param.args[0] as SurfaceHolder
                    logDebug("Original C1 SurfaceHolder saved", "$oriHolder", false, lpparam)

                    if (c1FakeTexture == null) {
                        c1FakeTexture = SurfaceTexture(11)
                    } else {
                        c1FakeTexture!!.release()
                        c1FakeTexture = SurfaceTexture(11)
                    }

                    // 這裡的邏輯是將相機的預覽目標從 SurfaceHolder 重定向到一個偽造的 SurfaceTexture
                    // 從而實現解耦
                    mcamera1!!.setPreviewTexture(c1FakeTexture)
                    logDebug("Redirected preview to fake SurfaceTexture", "Fake Texture: $c1FakeTexture", false, lpparam)

                    // 關鍵一步：將原始方法的執行結果設置為 null，這會阻止原始的 setPreviewDisplay 方法繼續執行
                    // 避免了 "Camera has already been released" 或 "Surface already has a consumer" 等錯誤
                    param.result = null
                    logDebug("Set method result to null to prevent original call", null, false, lpparam)

                } catch (t: Throwable) {
                    logError(t, "setPreviewDisplay", lpparam)
                }
            }
        })
        // >>>>> 新增/修改 END (setPreviewDisplay) <<<<<

        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // >>>>> 新增/修改 START <<<<<
                    logDebug("Hooked: C2.openCamera", "ID: ${param.args[0]}, Callback: ${param.args[1]}", true, lpparam)
                    try {
                    // >>>>> 新增/修改 END <<<<<
                        if(param.args[1] == null){
                            return
                        }
                        if(param.args[1] == c2_state_callback){
                            return
                        }
                        c2_state_callback = param.args[1] as CameraDevice.StateCallback
                        c2_state_callback_class = param.args[1]?.javaClass
                        // >>>>> 新增/修改 START <<<<<
                        logDebug("Original C2 StateCallback saved", "Class: ${c2_state_callback_class?.name}", false, lpparam)
                        // >>>>> 新增/修改 END <<<<<
                        process_camera2_init(c2_state_callback_class as Class<Any>?,lpparam)
                    // >>>>> 新增/修改 START <<<<<
                    }catch (t:Throwable){
                        logError(t, "openCamera", lpparam)
                    }
                    // >>>>> 新增/修改 END <<<<<
                }
            })
    }

    private fun process_callback(param: MethodHookParam) {
        val preview_cb_class: Class<*>? = param.args[0]?.javaClass
        if (preview_cb_class == null) {
            logDebug("process_callback failed: PreviewCallback class is null", null, false, null)
            return
        }
        
        logDebug("Executing process_callback", "Hooking onPreviewFrame for class: ${preview_cb_class.name}", false, null)

        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame",
            ByteArray::class.java,
            Camera::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(paramd: MethodHookParam) {
                    // 這個回調極其頻繁，只在第一次或發生錯誤時記錄
                    try {
                        val localcam = paramd.args[1] as Camera
                        if (localcam == camera_onPreviewFrame) {
                            // 持續提供數據幀
                            if (data_buffer != null) {
                                System.arraycopy(data_buffer, 0, paramd.args[0], 0, min(data_buffer.size.toDouble(), (paramd.args[0] as ByteArray).size.toDouble()).toInt())
                            }
                        } else {
                            // 第一次進入，或相機對象改變
                            logDebug("Hooked: C1.onPreviewFrame (First time)", "Camera: $localcam", true, null)

                            camera_callback_calss = preview_cb_class
                            camera_onPreviewFrame = paramd.args[1] as Camera
                            val mwidth = camera_onPreviewFrame!!.parameters.previewSize.width
                            val mheight = camera_onPreviewFrame!!.parameters.previewSize.height
                            
                            // 【修正】用我們的日誌系統替換掉不安全的 Toast
                            val sizeInfo = "Video resolution must match camera: W:$mwidth, H:$mheight"
                            logDebug(sizeInfo, null, true, null)

                            hw_decode_obj?.stopDecode()
                            hw_decode_obj = VideoToFrames()
                            hw_decode_obj!!.setSaveFrames(OutputImageFormat.NV21)

                            val videoUrl = "content://com.wangyiheng.vcamsx.videoprovider"
                            val videoPathUri = Uri.parse(videoUrl)
                            hw_decode_obj!!.decode(videoPathUri)
                            logDebug("VideoToFrames decoding started for C1 callback", null, false, null)
                            
                            // 第一次填充數據
                            if (data_buffer != null) {
                                System.arraycopy(data_buffer, 0, paramd.args[0], 0, min(data_buffer.size.toDouble(), (paramd.args[0] as ByteArray).size.toDouble()).toInt())
                            }
                        }
                    } catch (t: Throwable) {
                        // 為這個內部Hook添加錯誤捕獲
                        logError(t, "onPreviewFrame", null)
                    }
                }
            })
    }

    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        logDebug("Executing process_camera2_init", "Class: ${c2StateCallbackClass?.name}", false, lpparam)
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                // >>>>> 新增/修改 START <<<<<
                logDebug("Hooked: C2.onOpened", "Device: ${param.args[0]}", true, lpparam)
                try {
                // >>>>> 新增/修改 END <<<<<
                    needRecreate = true
                    createVirtualSurface()

                    c2_reader_Surfcae = null
                    original_preview_Surface = null

                    if(lpparam.packageName != "com.ss.android.ugc.aweme" ){
                        XposedHelpers.findAndHookMethod(param.args[0].javaClass, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
                            @Throws(Throwable::class)
                            override fun beforeHookedMethod(paramd: MethodHookParam) {
                                // >>>>> 新增/修改 START <<<<<
                                logDebug("Hooked: C2.createCaptureSession (List)", "Surfaces: ${paramd.args[0]}", true, lpparam)
                                try {
                                // >>>>> 新增/修改 END <<<<<
                                    if (paramd.args[0] != null) {
                                        paramd.args[0] = listOf(c2_virtual_surface)
                                        logDebug("Replaced with virtual surface", null, false, lpparam)
                                    }
                                // >>>>> 新增/修改 START <<<<<
                                } catch(t: Throwable) {
                                    logError(t, "createCaptureSession(List)", lpparam)
                                }
                                // >>>>> 新增/修改 END <<<<<
                            }
                        })
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            XposedHelpers.findAndHookMethod(param.args[0].javaClass, "createCaptureSession",
                                SessionConfiguration::class.java, object : XC_MethodHook() {
                                    @Throws(Throwable::class)
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        // >>>>> 新增/修改 START <<<<<
                                        logDebug("Hooked: C2.createCaptureSession (Config)", "Config: ${param.args[0]}", true, lpparam)
                                        try {
                                        // >>>>> 新增/修改 END <<<<<
                                            super.beforeHookedMethod(param)
                                            if (param.args[0] != null) {
                                                sessionConfiguration = param.args[0] as SessionConfiguration
                                                outputConfiguration = OutputConfiguration(c2_virtual_surface!!)
                                                fake_sessionConfiguration = SessionConfiguration(
                                                    sessionConfiguration!!.getSessionType(),
                                                    Arrays.asList<OutputConfiguration>(outputConfiguration),
                                                    sessionConfiguration!!.getExecutor(),
                                                    sessionConfiguration!!.getStateCallback()
                                                )
                                                param.args[0] = fake_sessionConfiguration
                                                logDebug("Replaced with fake SessionConfiguration", null, false, lpparam)
                                            }
                                        // >>>>> 新增/修改 START <<<<<
                                        } catch(t: Throwable) {
                                            logError(t, "createCaptureSession(Config)", lpparam)
                                        }
                                        // >>>>> 新增/修改 END <<<<<
                                    }
                                })
                        }
                    }
                // >>>>> 新增/修改 START <<<<<
                } catch(t: Throwable) {
                    logError(t, "onOpened", lpparam)
                }
                // >>>>> 新增/修改 END <<<<<
            }
        })


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // >>>>> 新增/修改 START <<<<<
                    // 這個方法會頻繁調用，所以預設不在懸浮窗上顯示，避免刷屏
                    logDebug("Hooked: C2.addTarget", "Surface: ${param.args[0]}", false, lpparam)
                    try {
                    // >>>>> 新增/修改 END <<<<<
                        if (param.args[0] != null) {
                            if(param.args[0] == c2_virtual_surface)return
                            val surfaceInfo = param.args[0].toString()
                            if (!surfaceInfo.contains("Surface(name=null)")) {
                                if(original_preview_Surface != param.args[0] as Surface ){
                                    original_preview_Surface = param.args[0] as Surface
                                    // >>>>> 新增/修改 START <<<<<
                                    // 只有在捕獲到有效的預覽Surface時，才更新懸浮窗
                                    logDebug("C2 Original Surface saved!", "Surface: $original_preview_Surface", true, lpparam)
                                    // >>>>> 新增/修改 END <<<<<
                                }
                            }else{
                                if(c2_reader_Surfcae == null && lpparam.packageName != "com.ss.android.ugc.aweme"){
                                    c2_reader_Surfcae = param.args[0] as Surface
                                }
                            }
                            if(lpparam.packageName != "com.ss.android.ugc.aweme"){
                                param.args[0] = c2_virtual_surface
                            }
                        }
                    // >>>>> 新增/修改 START <<<<<
                    } catch(t: Throwable) {
                        logError(t, "addTarget", lpparam)
                    }
                    // >>>>> 新增/修改 END <<<<<
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "build",object :XC_MethodHook(){
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                // >>>>> 新增/修改 START <<<<<
                logDebug("Hooked: C2.build", "Triggering camera2Play()", true, lpparam)
                try {
                // >>>>> 新增/修改 END <<<<<
                    camera2Play()
                // >>>>> 新增/修改 START <<<<<
                } catch(t: Throwable) {
                    logError(t, "build", lpparam)
                }
                // >>>>> 新增/修改 END <<<<<
            }
        })
    }

    private fun createVirtualSurface(): Surface? {
        if (needRecreate) {
            c2VirtualSurfaceTexture?.release()
            c2VirtualSurfaceTexture = null

            c2_virtual_surface?.release()
            c2_virtual_surface = null

            c2VirtualSurfaceTexture = SurfaceTexture(15)
            c2_virtual_surface = Surface(c2VirtualSurfaceTexture)
            needRecreate = false
        } else if (c2_virtual_surface == null) {
            needRecreate = true
            c2_virtual_surface = createVirtualSurface()
        }
        return c2_virtual_surface
    }
}
