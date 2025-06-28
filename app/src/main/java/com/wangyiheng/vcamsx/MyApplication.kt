package com.wangyiheng.vcamsx

import android.app.Application
import com.wangyiheng.vcamsx.data.di.appModule
import com.wangyiheng.vcamsx.data.services.networkModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.DataOutputStream
import java.io.File
import android.util.Log

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Koin
        startKoin {
            // Declare modules to use
            androidContext(this@MyApplication)
            modules(appModule,networkModule)
        }
		createGlobalVideoFolder()
    }
	
    private fun createGlobalVideoFolder() {
        // 使用一個不易衝突且語義明確的路徑
        val videoFolderPath = "/data/local/tmp/vcamsx_video"
        val videoFolder = File(videoFolderPath)

        if (!videoFolder.exists()) {
            Log.d("VCAMSX_ROOT", "Global folder doesn't exist. Creating with root...")
            try {
                // 創建一個 su 進程來執行多條命令
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                // 創建文件夾
                os.writeBytes("mkdir -p $videoFolderPath\n")
                // 賦予最高權限：讀、寫、執行對所有用戶開放
                os.writeBytes("chmod 777 $videoFolderPath\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
                Log.d("VCAMSX_ROOT", "Global folder created and permissions set.")
            } catch (e: Exception) {
                Log.e("VCAMSX_ROOT", "Failed to create global folder with root", e)
                // 這裡可以給用戶一個 Toast 提示，告知需要 Root 權限
            }
        } else {
            Log.d("VCAMSX_ROOT", "Global folder already exists.")
        }
    }
}
