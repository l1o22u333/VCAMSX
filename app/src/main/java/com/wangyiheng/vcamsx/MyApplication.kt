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
        val videoFolderPath = "/data/local/tmp/vcamsx_video"
        val videoFolder = File(videoFolderPath)
        
        // 期望的權限和 SELinux 上下文
        val expectedPermissions = "drwxrwxrwx" // d 表示目錄，rwx rwx rwx 表示 777
        val expectedSELinuxContext = "u:object_r:app_data_file:s0"

        var permissionsAreCorrect = false
        var contextIsCorrect = false

        if (videoFolder.exists()) {
            // >>>>> 【健壯性增強】START：如果文件夾已存在，檢查其權限 <<<<<
            Log.d("VCAMSX_ROOT", "Global folder exists. Verifying permissions and context...")
            try {
                // 執行 ls -lZ 命令來獲取權限和 SELinux 上下文
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls -lZ $videoFolderPath"))
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val output = reader.readLine() // 預期輸出類似 "drwxrwxrwx 1 shell shell u:object_r:app_data_file:s0 2024-06-28 10:00 vcamsx_video"
                reader.close()
                process.waitFor()

                if (output != null) {
                    Log.d("VCAMSX_ROOT", "Current attributes: $output")
                    val parts = output.split("\\s+".toRegex()) // 用一個或多個空格分割
                    if (parts.isNotEmpty()) {
                        val currentPermissions = parts[0]
                        val currentSELinuxContext = parts.find { it.contains("u:object_r:") }

                        permissionsAreCorrect = (currentPermissions == expectedPermissions)
                        contextIsCorrect = (currentSELinuxContext == expectedSELinuxContext)

                        Log.d("VCAMSX_ROOT", "Permission check: $permissionsAreCorrect, SELinux context check: $contextIsCorrect")
                    }
                } else {
                    Log.w("VCAMSX_ROOT", "Could not read attributes of the existing folder.")
                }
            } catch (e: Exception) {
                Log.e("VCAMSX_ROOT", "Error verifying folder attributes", e)
            }
            // >>>>> 【健壯性增強】END <<<<<
        }
        
        // 如果文件夾不存在，或者權限/上下文不正確，則強制執行創建和設置命令
        if (!videoFolder.exists() || !permissionsAreCorrect || !contextIsCorrect) {
            val action = if (!videoFolder.exists()) "Creating" else "Fixing"
            Log.d("VCAMSX_ROOT", "$action global folder and setting permissions...")
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                
                // 即使文件夾已存在，mkdir -p 也不會報錯，是安全的
                os.writeBytes("mkdir -p $videoFolderPath\n")
                
                // 強制設置正確的權限和上下文
                os.writeBytes("chmod 777 $videoFolderPath\n")
                os.writeBytes("chcon $expectedSELinuxContext $videoFolderPath\n")
                
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
                
                Log.d("VCAMSX_ROOT", "Global folder creation/fix process completed.")

            } catch (e: Exception) {
                Log.e("VCAMSX_ROOT", "Failed to create/fix global folder with root", e)
            }
        } else {
            Log.d("VCAMSX_ROOT", "Permissions and context are already correct. No action needed.")
        }
    }
}
