package com.wangyiheng.vcamsx.utils // 確保這個包名和您放置文件的目錄一致

// ======================= 新增/補全的 import 語句 START =======================
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
// ======================= 新增/補全的 import 語句 END =======================


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
                    // 只創建一次
                    windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    
                    // 從上下文中獲取 LayoutInflater，而不是直接創建
                    val layoutInflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

                    // 創建懸浮窗視圖
                    overlayView = FrameLayout(context).apply {
                        setBackgroundColor(Color.parseColor("#80000000")) // 半透明黑色背景
                    }
                    textView = TextView(context).apply {
                        setTextColor(Color.WHITE)
                        setTextSize(10f)
                        setPadding(10, 10, 10, 10)
                        text = initialMessage // 初始時就設置文字
                    }
                    (overlayView as FrameLayout).addView(textView)

                    // 設置佈局參數
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

                    // 添加觸摸監聽器以實現拖動
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

                // 如果懸浮窗已存在，僅更新文字
                updateText(initialMessage)

            } catch (e: Exception) {
                XposedBridge.log("[VCAMSX Debug] Error showing overlay: ${e.message}")
                e.printStackTrace(System.err) // 打印更詳細的錯誤到 logcat
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
                    XposedBridge.log("[VCAMSX Debug] Error hiding overlay: ${e.message}")
                } finally {
                    overlayView = null
                    windowManager = null
                    textView = null
                }
            }
        }
    }
}
