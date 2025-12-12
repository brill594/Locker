package com.brill.locker

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuHelper {

    fun isShizukuAvailable(): Boolean {
        return Shizuku.pingBinder()
    }

    fun checkPermission(code: Int): Boolean {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            return false
        }
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(code: Int) {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            return
        }
        Shizuku.requestPermission(code)
    }

    fun forceStartHome(packageName: String, className: String) {
        // am start -a android.intent.action.MAIN -c android.intent.category.HOME -n 包名/类名
        val cmd = "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $packageName/$className"
        runShellCommand(cmd)
    }
    fun setHomeRole(packageName: String) {
        val cmd1 = "cmd role clear-role-holders android.app.role.HOME"
        runShellCommand(cmd1)
        val cmd = "cmd role add-role-holder android.app.role.HOME $packageName"
        runShellCommand(cmd)
    }
    // [原有] 设置默认桌面配置
    fun setDeviceLauncher(packageName: String, className: String) {
        val cmd = "cmd package set-home-activity $packageName/$className"
        runShellCommand(cmd)
    }

    // --- 修复后的反射逻辑 ---
    private fun newProcessCompat(cmd: Array<String>): java.lang.Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            // 强转为 java.lang.Process
            method.invoke(null, cmd, null, null) as? java.lang.Process
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    fun setZenMode(mode: Int) {
        val cmd = "settings put global zen_mode $mode"
        runShellCommand(cmd)
    }
    fun grantNotificationPermission(packageName: String) {
        // cmd appops set <包名> ACCESS_NOTIFICATION_POLICY allow
        val cmd = "cmd appops set $packageName ACCESS_NOTIFICATION_POLICY allow"
        runShellCommand(cmd)
    }
    fun runShellCommand(command: String): String {
        return try {
            // 明确调用内部的私有方法
            val process = newProcessCompat(arrayOf("sh", "-c", command))

            // 智能转换：如果 process 为空直接返回错误
            if (process == null) return "Error: Reflection failed"

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                output.append(line).append("\n")
                line = reader.readLine()
            }

            process.waitFor()
            "Output:\n$output"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}