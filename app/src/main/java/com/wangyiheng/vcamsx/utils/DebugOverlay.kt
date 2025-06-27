// 文件名：DebugOverlay.kt
package com.wangyiheng.vcamsx.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import java.io.PrintWriter
import java.io.StringWriter

object DebugOverlay {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, initialMessage: String) {
        mainHandler.post {
            try {
                if (overlayView == null) {
                    windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    
                    val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

                    overlayView = FrameLayout(context).apply {
                        setBackgroundColor(Color.parseColor("#80000000")) 
                    }
                    textView = TextView(context).apply {
                        setTextColor(Color.WHITE)
                        setTextSize(10f)
                        setPadding(10, 10, 10, 10)
                        text = initialMessage
                    }
                    (overlayView as FrameLayout).addView(textView)

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 0
                        y = 100
                    }

                    overlayView?.setOnTouchListener(object : View.OnTouchListener {
                        private var initialX: Int = 0
                        private var initialY: Int = 0
                        private var initialTouchX: Float = 0.toFloat()
                        private var initialTouchY: Float = 0.toFloat()

                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    initialX = params.x
                                    initialY = params.y
                                    initialTouchX = event.rawX
                                    initialTouchY = event.rawY
                                    return true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                                    windowManager?.updateViewLayout(overlayView, params)
                                    return true
                                }
                            }
                            return false
                        }
                    })

                    windowManager?.addView(overlayView, params)
                }

                updateText(initialMessage)

            } catch (e: Exception) {
                // >>>>> 【修正】統一錯誤日誌的處理方式 START <<<<<
                val logTitle = "[VCAMSX_DEBUG] !!! ERROR in DebugOverlay.show: ${e.message}"
                
                // 將異常堆棧轉換為字串
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stackTraceString = sw.toString()

                // 拼接完整的、帶有前綴的日誌資訊
                val fullLogMessage = buildString {
                    appendLine(logTitle)
                    stackTraceString.lines().forEach { line ->
                        append("[VCAMSX_DEBUG] \t")
                        appendLine(line)
                    }
                }
                XposedBridge.log(fullLogMessage)
                // >>>>> 【修正】統一錯誤日誌的處理方式 END <<<<<
            }
        }
    }

    fun updateText(message: String) {
        mainHandler.post {
            textView?.let {
                it.text = message
            }
        }
    }

    fun hide() {
        mainHandler.post {
            if (overlayView != null && windowManager != null) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    // >>>>> 【修正】統一錯誤日誌的處理方式 START <<<<<
                    val logTitle = "[VCAMSX_DEBUG] !!! ERROR in DebugOverlay.hide: ${e.message}"

                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    val stackTraceString = sw.toString()
                    
                    val fullLogMessage = buildString {
                        appendLine(logTitle)
                        stackTraceString.lines().forEach { line ->
                            append("[VCAMSX_DEBUG] \t")
                            appendLine(line)
                        }
                    }
                    XposedBridge.log(fullLogMessage)
                    // >>>>> 【修正】統一錯誤日誌的處理方式 END <<<<<
                } finally {
                    overlayView = null
                    windowManager = null
                    textView = null
                }
            }
        }
    }
}
