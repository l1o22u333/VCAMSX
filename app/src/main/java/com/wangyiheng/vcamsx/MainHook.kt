// 文件名：MainHook.kt
package com.wangyiheng.vcamsx
import android.app.Application
import android.content.Context
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
            //if (toOverlay) {
               // context?.let { ctx -> DebugOverlay.show(ctx, message) }
           // }
        }

        /**
         * 統一的錯誤記錄器
         * @param t 捕獲到的異常
         * @param fromFunction 發生錯誤的函數名
         * @param lpparam 可選的LoadPackageParam
         */
        private fun logError(t: Throwable, fromFunction: String, lpparam: XC_LoadPackage.LoadPackageParam?) {
            // 1. 準備一個簡短的錯誤資訊，用於懸浮窗顯示
            val errorMessageForOverlay = "!!! ERROR in $fromFunction: ${t.javaClass.simpleName}"
        
            // 2. 在懸浮窗上醒目地顯示簡短的錯誤
            //context?.let { ctx -> DebugOverlay.show(ctx, errorMessageForOverlay) }
        
            // 3. 【修正點】定義 logTitle，這是日誌的第一行標題
            val logTitle = buildString {
                append("[VCAMSX_DEBUG] ")
                if (lpparam != null) append("[${lpparam.processName}] ")
                // 在詳細日誌中，我們可以包含更完整的錯誤訊息
                append("!!! ERROR in $fromFunction: ${t.javaClass.simpleName} - ${t.message}")
            }
        
            // 4. 將異常堆疊轉換為字串
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            t.printStackTrace(pw)
            val stackTraceString = sw.toString()
        
            // 5. 組合標題和帶有前綴的堆疊資訊，成為一個完整的日誌字串
            val fullLogMessage = buildString {
                appendLine(logTitle) // <-- 現在 logTitle 是已定義的
                stackTraceString.lines().forEach { line ->
                    // 為了避免過濾掉任何資訊，即使是空行，我們也給它加上前綴
                    append("[VCAMSX_DEBUG] \t") // 使用 \t 縮進，讓堆疊資訊在視覺上更清晰
                    appendLine(line)
                }
            }
        
            // 6. 將拼接好的、帶有完整前綴的日誌一次性打印
            XposedBridge.log(fullLogMessage)
        }
    }
    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null

    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // >>>>> 新增/修改 START <<<<<
        logDebug("V1.1 Module loading", "Package: ${lpparam.packageName}", false, lpparam)
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
        // >>>>> 【終極偵察包 - 1】START：Hook 相機資訊查詢 <<<<<
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
                String::class.java,                     // cameraId
                java.util.concurrent.Executor::class.java, // executor
                CameraDevice.StateCallback::class.java,  // callback
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 我們捕獲到了 Executor 版本的 openCamera 調用！
                        logDebug(
                            "Hooked: C2.openCamera (Executor Version)",
                            "ID: ${param.args[0]}, Executor: ${param.args[1]}, Callback: ${param.args[2]}",
                            true, lpparam
                        )
                        try {
                            // 後續的邏輯和 Handler 版本完全一樣
                            if (param.args[2] == null) {
                                return
                            }
                            if (param.args[2] == c2_state_callback) {
                                return
                            }
                            c2_state_callback = param.args[2] as CameraDevice.StateCallback
                            c2_state_callback_class = param.args[2]?.javaClass
                            
                            logDebug("Original C2 StateCallback saved (from Executor version)", "Class: ${c2_state_callback_class?.name}", false, lpparam)
                            
                            // 調用我們統一的後續處理函數
                            process_camera2_init(c2_state_callback_class as Class<Any>?, lpparam)
                            
                        } catch (t: Throwable) {
                            logError(t, "openCamera(Executor)", lpparam)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            // 如果這個版本的 Hook 找不到，也不要緊，只是記錄一下
            logError(t, "Hooking openCamera(Executor)", lpparam)
        }
        // >>>>> 【修正策略】END <<<<<
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "getNumberOfCameras",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: Camera.getNumberOfCameras (Static)",
                            "Result: ${param.result}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking getNumberOfCameras", lpparam)
        }
        // >>>>> 【終極偵察包 - 2】START：Hook Camera.open 的所有版本 <<<<<
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "open",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: Camera.open() - No-Arg Version",
                            "Return (Camera obj): ${param.result}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking Camera.open()", lpparam)
        }
        
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "open",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: Camera.open(int) - Int-Arg Version",
                            "CameraId: ${param.args[0]}, Return (Camera obj): ${param.result}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking Camera.open(int)", lpparam)
        }
        // >>>>> 【終極偵察包 - 2】END <<<<<
        // >>>>> 【終極偵察包 - 3】START：Hook Surface 的構造函數 <<<<<
        try {
            XposedHelpers.findAndHookConstructor(
                "android.view.Surface", lpparam.classLoader,
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: Surface CONSTRUCTOR (from SurfaceTexture)",
                            "Input ST: ${param.args[0]}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking Surface(SurfaceTexture)", lpparam)
        }
        // >>>>> 【終極偵察包 - 3】END <<<<<
        // >>>>> 【終極偵察包 - 4】START：Hook Camera 與系統服務的連接點 <<<<<
        try {
            // 這個方法在 Camera 類內部，是與底層服務通信的關鍵
            // public static Camera open(int cameraId) {
            //     ...
            //     c = new Camera(cameraId);
            //     c.connect(c, cameraId, c.mClient, c.mAppContext);  <-- 我們在 Hook Camera 的構造函數後，再 Hook connect
            //     ...
            // }
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "connect",
                Camera::class.java, Int::class.java, String::class.java, Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: Camera.connect (ULTRA-LOW LEVEL)",
                            "CameraId: ${param.args[1]}, Calling Pkg: ${param.args[2]}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (e: NoSuchMethodError) {
             // 在某些極高或極低的Android版本上，這個方法的簽名可能不同，所以要捕獲NoSuchMethodError
             logDebug("Camera.connect with 4 args not found, trying alternatives...", null, false, lpparam)
        } catch (t: Throwable) {
            logError(t, "Hooking Camera.connect", lpparam)
        }
        // >>>>> 【終極偵察包 - 4】END <<<<<
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "getCameraInfo",
                Int::class.java, Camera.CameraInfo::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: Camera.getCameraInfo (Static)",
                            "CameraId: ${param.args[0]}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking getCameraInfo", lpparam)
        }
        // >>>>> 【終極偵察包 - 1】END <<<<<
        // >>>>> 【終極偵察包 - 5】START：Hook Camera2 的入口點 <<<<<
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "getCameraIdList",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: C2.getCameraIdList",
                            "Return (ID List): ${(param.result as? Array<*>)?.contentToString()}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking getCameraIdList", lpparam)
        }
        // >>>>> 【終極偵察包 - 5】END <<<<<
        // >>>>> 【終極偵察包 - 6】START：Hook Camera2 的特性查詢 <<<<<
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "getCameraCharacteristics",
                String::class.java, // 參數是 cameraId
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        logDebug(
                            "Hooked: C2.getCameraCharacteristics",
                            "Input (CameraId): ${param.args[0]}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking getCameraCharacteristics", lpparam)
        }
        // >>>>> 【終極偵察包 - 6】END <<<<<
        // >>>>> 【精準打擊 - 7】START：Hook 權限請求 <<<<<
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", lpparam.classLoader, "requestPermissions",
                Array<String>::class.java, // permissions
                Int::class.java,          // requestCode
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val permissions = param.args[0] as Array<String>
                        // 檢查請求的權限中是否包含相機權限
                        if (permissions.contains("android.permission.CAMERA")) {
                            logDebug(
                                "Hooked: Activity.requestPermissions (FOR CAMERA)",
                                "Activity: ${param.thisObject::class.java.name}\n\t> Permissions: ${permissions.contentToString()}\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                                true, lpparam
                            )
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking requestPermissions", lpparam)
        }
        // >>>>> 【精準打擊 - 7】END <<<<<
        // >>>>> 【精準打擊 - 8】START：Hook MediaRecorder 連接相機 <<<<<
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.MediaRecorder", lpparam.classLoader, "setCamera",
                Camera::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cameraObj = param.args[0] as Camera
                        logDebug(
                            "Hooked: MediaRecorder.setCamera",
                            "MediaRecorder: ${param.thisObject}\n\t> Camera Obj: $cameraObj\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                            true, lpparam
                        )
                        // 在這裡，我們已經捕獲到了 Camera 對象！
                        // 可以在這裡執行我們自己的替換邏輯，或者根據堆棧分析是誰調用了它。
                        // 例如: origin_preview_camera = cameraObj;
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking MediaRecorder.setCamera", lpparam)
        }
        // >>>>> 【精準打擊 - 8】END <<<<<
        // >>>>> 【精準打擊 - 9】START：Hook 底層 EGL 表面創建 <<<<<
        try {
            XposedHelpers.findAndHookMethod(
                "android.opengl.EGL14", lpparam.classLoader, "eglCreateWindowSurface",
                android.opengl.EGLDisplay::class.java, // dpy
                android.opengl.EGLConfig::class.java,  // config
                Any::class.java,                       // win (可能是 Surface, SurfaceHolder, SurfaceTexture)
                IntArray::class.java,                  // attrib_list
                Int::class.java,                       // offset
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val windowObject = param.args[2]
                        // 我們只關心與 Surface 相關的調用
                        if (windowObject is Surface || windowObject is SurfaceHolder || windowObject is SurfaceTexture) {
                            logDebug(
                                "Hooked: EGL14.eglCreateWindowSurface",
                                "Window Object Type: ${windowObject::class.java.simpleName}\n\t> Object: $windowObject\n\t> StackTrace:\n${android.util.Log.getStackTraceString(Throwable())}",
                                true, lpparam
                            )
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking eglCreateWindowSurface", lpparam)
        }
        // >>>>> 【精準打擊 - 9】END <<<<<
        try {
            XposedHelpers.findAndHookConstructor(
                "android.graphics.SurfaceTexture", lpparam.classLoader,
                Int::class.java, // 對應 public SurfaceTexture(int texName)
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 在 SurfaceTexture 被創建之後記錄日誌
                        val stObject = param.thisObject as SurfaceTexture
                        val callingStackTrace = Throwable() // 創建一個 Throwable 來獲取調用堆棧
                        
                        logDebug(
                            "Hooked: SurfaceTexture CONSTRUCTOR (int)",
                            "Created ST: $stObject\n\t> StackTrace:\n${android.util.Log.getStackTraceString(callingStackTrace)}",
                            true, // 在懸浮窗上顯示，給我們一個強信號
                            lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking SurfaceTexture(int)", lpparam)
        }

        try {
             XposedHelpers.findAndHookConstructor(
                "android.graphics.SurfaceTexture", lpparam.classLoader,
                Boolean::class.java, // 對應 public SurfaceTexture(boolean singleBufferMode)
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stObject = param.thisObject as SurfaceTexture
                        val callingStackTrace = Throwable()
                        
                        logDebug(
                            "Hooked: SurfaceTexture CONSTRUCTOR (boolean)",
                            "Created ST: $stObject\n\t> StackTrace:\n${android.util.Log.getStackTraceString(callingStackTrace)}",
                            true,
                            lpparam
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            logError(t, "Hooking SurfaceTexture(boolean)", lpparam)
        }
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
        logDebug("Executing process_camera2_init", "Target Class: ${c2StateCallbackClass?.name}", false, lpparam)

        // onOpened 的職責：獲取具體的 CameraDevice 實例，並對其所有 createCaptureSession 方法進行 Hook
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                logDebug("Hooked: C2.onOpened", "Device: ${param.args[0]}", true, lpparam)
                try {
                    // 1. 創建我們的虛擬 Surface
                    needRecreate = true
                    createVirtualSurface()
                    logDebug("Virtual surface is ready", "Surface: $c2_virtual_surface", false, lpparam)

                    c2_reader_Surfcae = null
                    original_preview_Surface = null

                    // 2. 【關鍵修正】遍歷此 CameraDevice 實例的所有方法，Hook 所有名為 createCaptureSession 的方法
                    val cameraDeviceInstance = param.args[0] as? CameraDevice ?: return
                    val methods = cameraDeviceInstance::class.java.declaredMethods
                    var hookedCount = 0

                    // 3. 創建一個可重用的 Hook 回調
                    val captureSessionHook = object : XC_MethodHook() {
                        override fun beforeHookedMethod(methodParam: MethodHookParam) {
                            val firstArg = methodParam.args.getOrNull(0)
                            
                            // >>>>> 【最終修正 V3】START：使用最穩健的方式拼接日誌 <<<<<
                            val method = methodParam.method
                            val paramTypeNames = mutableListOf<String>()
                            for (type in method.parameterTypes) {
                                paramTypeNames.add(type.simpleName)
                            }
                            val paramTypesString = paramTypeNames.joinToString(", ")
                            val signatureString = "Signature: ${method.name}($paramTypesString)\n\t> Arg[0]: $firstArg"
                            // >>>>> 【最終修正 V3】END <<<<<
                            
                            logDebug(
                                "Hooked: C2.createCaptureSession (DYNAMICALLY)",
                                signatureString, // 使用修正後的字串
                                true, lpparam
                            )
                            try {
                                when (firstArg) {
                                    is SessionConfiguration -> {
                                        val originalConfig = firstArg
                                        val outputConfig = OutputConfiguration(c2_virtual_surface!!)
                                        val fakeConfig = SessionConfiguration(
                                            originalConfig.sessionType,
                                            listOf(outputConfig),
                                            originalConfig.executor,
                                            originalConfig.stateCallback
                                        )
                                        methodParam.args[0] = fakeConfig
                                        logDebug("Replaced SessionConfiguration with fake config", null, false, lpparam)
                                    }
                                    is List<*> -> {
                                        methodParam.args[0] = listOf(c2_virtual_surface)
                                        logDebug("Replaced List<Surface> with fake surface list", null, false, lpparam)
                                    }
                                    else -> {
                                        logDebug("Unsupported createCaptureSession variant found", "First arg type: ${firstArg?.javaClass?.name}", false, lpparam)
                                    }
                                }
                            } catch (t: Throwable) {
                                logError(t, "createCaptureSession_dynamic_hook", lpparam)
                            }
                        }
                    }

                    // 4. 遍歷並 Hook
                    for (method in methods) {
                        if (method.name == "createCaptureSession") {
                            XposedBridge.hookMethod(method, captureSessionHook)
                            hookedCount++
                        }
                    }
                    
                    if (hookedCount > 0) {
                        logDebug("Dynamically hooked $hookedCount createCaptureSession method(s)", null, false, lpparam)
                    } else {
                        logDebug("Warning: No createCaptureSession methods found to hook in ${cameraDeviceInstance::class.java}", null, true, lpparam)
                    }

                } catch (t: Throwable) {
                    logError(t, "onOpened", lpparam)
                }
            }
        })

        // 對 Builder 的 Hook 保持不變
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader, "addTarget", android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logDebug("Hooked: C2.addTarget", "Surface: ${param.args[0]}", false, lpparam)
                    try {
                        if (param.args[0] != null) {
                            if(param.args[0] == c2_virtual_surface)return
                            val surfaceInfo = param.args[0].toString()
                            if (!surfaceInfo.contains("Surface(name=null)")) {
                                if(original_preview_Surface != param.args[0] as Surface ){
                                    original_preview_Surface = param.args[0] as Surface
                                    logDebug("C2 Original Surface saved!", "Surface: $original_preview_Surface", true, lpparam)
                                }
                            } else {
                                if(c2_reader_Surfcae == null && lpparam.packageName != "com.ss.android.ugc.aweme"){
                                    c2_reader_Surfcae = param.args[0] as Surface
                                }
                            }
                            if(lpparam.packageName != "com.ss.android.ugc.aweme"){
                                param.args[0] = c2_virtual_surface
                            }
                        }
                    } catch(t: Throwable) {
                        logError(t, "addTarget", lpparam)
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader, "build", object :XC_MethodHook(){
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logDebug("Hooked: C2.build", "Triggering camera2Play()", true, lpparam)
                    try {
                        camera2Play()
                    } catch(t: Throwable) {
                        logError(t, "build", lpparam)
                    }
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
