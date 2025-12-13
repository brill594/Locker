package com.brill.locker

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

// [新增] 用于承载完整 Shell 结果的数据类
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    // 方便打印日志的方法
    fun isSuccess() = exitCode == 0
    override fun toString(): String {
        return "ExitCode: $exitCode\nSTDOUT: $stdout\nSTDERR: $stderr"
    }
}

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    fun isShizukuAvailable(): Boolean = Shizuku.pingBinder()

    fun checkPermission(code: Int): Boolean {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(code: Int) {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return
        Shizuku.requestPermission(code)
    }
    fun getHomeActivityFromShell(): Pair<String, String>? {
        // --brief 参数让输出更简洁，方便解析
        val cmd = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        val result = runShellCommand(cmd)

        if (result.exitCode == 0) {
            val output = result.stdout
            // 输出示例：
            // priority=0 preferredOrder=0 ... isDefault=true
            // com.microsoft.launcher/.Launcher

            // 解析逻辑：找到包含 "/" 的那一行
            val lines = output.split("\n")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("/")) {
                    val parts = trimmed.split("/")
                    if (parts.size == 2) {
                        val pkg = parts[0]
                        var cls = parts[1]

                        // 处理简写类名 (例如 .Launcher -> com.microsoft.launcher.Launcher)
                        if (cls.startsWith(".")) {
                            cls = pkg + cls
                        }

                        // 再次过滤无效包名 (防止 Shell 也返回 ResolverActivity)
                        if (pkg != "android" && pkg != "com.android.internal.app") {
                            return Pair(pkg, cls)
                        }
                    }
                }
            }
        }
        return null
    }
    // --- 各种业务命令 ---

    // [修正] 设置 Activity 偏好 (加上 --user 0)
    // 语法：cmd package set-home-activity [--user USER_ID] TARGET-COMPONENT
    fun setDeviceLauncher(packageName: String, className: String) {
        val cmd = "cmd package set-home-activity --user 0 $packageName/$className"
        runShellCommand(cmd)
    }

    // [保留] 清除默认设置 (同样加上 --user 0 以防万一)
    fun clearPreferredActivities(packageName: String) {
        val cmd = "pm clear-package-preferred-activities --user 0 $packageName"
        runShellCommand(cmd)
    }
    fun removeHomeRole(packageName: String) {
        val cmd = "cmd role remove-role-holder --user 0 android.app.role.HOME $packageName"
        runShellCommand(cmd)
    }

    // [修正] 设置角色：把 --user 0 放到 android.app.role.HOME 之前
    fun setHomeRole(packageName: String) {
        val cmd = "cmd role add-role-holder --user 0 android.app.role.HOME $packageName"
        runShellCommand(cmd)
    }

    fun forceStartHome(packageName: String, className: String) {
        val cmd = "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $packageName/$className"
        runShellCommand(cmd)
    }

    fun grantNotificationPermission(packageName: String) {
        val cmd = "cmd appops set $packageName ACCESS_NOTIFICATION_POLICY allow"
        runShellCommand(cmd)
    }

    // --- 核心执行模块 (升级版) ---

    private fun newProcessCompat(cmd: Array<String>): java.lang.Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? java.lang.Process
        } catch (e: Exception) {
            Log.e(TAG, "Reflection failed", e)
            null
        }
    }

    // [修改] 返回 ShellResult 而不是 String
    fun runShellCommand(command: String): ShellResult {
        Log.d(TAG, "Exec: $command")
        return try {
            val process = newProcessCompat(arrayOf("sh", "-c", command))
            if (process == null) {
                return ShellResult(-1, "", "Failed to spawn process (reflection error)")
            }

            // 读取 stdout
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stdout = StringBuilder()
            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }

            // 读取 stderr (关键！)
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            val stderr = StringBuilder()
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            // 等待退出码
            val exitCode = process.waitFor()

            val result = ShellResult(exitCode, stdout.toString().trim(), stderr.toString().trim())

            // 直接在 Logcat 打印结果，方便你调试
            if (exitCode != 0) {
                Log.e(TAG, "Command FAILED:\n$result")
            } else {
                Log.i(TAG, "Command SUCCESS:\n$result")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception running command", e)
            ShellResult(-2, "", e.message ?: "Unknown error")
        }
    }
}