package com.brill.locker

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LockViewModel(application: Application) : AndroidViewModel(application) {

    // 状态变量
    var isLocked by mutableStateOf(false)
        private set

    var timeLeftSeconds by mutableStateOf(0L)
        private set

    var isAccessibilityReady by mutableStateOf(false)
        private set

    // 调试弹窗状态
    var showDebugDialog by mutableStateOf(false)
    var debugOutput by mutableStateOf("")

    private var timerJob: Job? = null
    companion object {
        var isGlobalLocked = false
    }

    private val PREF_NAME = "locker_prefs"
    private val KEY_PKG = "original_pkg"
    private val KEY_CLS = "original_cls"
    private val KEY_UNLOCK_TIMESTAMP = "unlock_timestamp"
    private val KEY_VOL_PREFIX = "vol_stream_"

    init {
        checkAccessibilityStatus()
        checkLockState()
    }

    private fun checkAccessibilityStatus() {
        viewModelScope.launch {
            while (true) {
                isAccessibilityReady = AccessibilityBlocker.isServiceConnected
                delay(1000)
            }
        }
    }

    private fun checkLockState() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val unlockTimestamp = prefs.getLong(KEY_UNLOCK_TIMESTAMP, 0L)
        val currentTimestamp = System.currentTimeMillis()

        if (unlockTimestamp > currentTimestamp) {
            val remainingMillis = unlockTimestamp - currentTimestamp
            isLocked = true
            timeLeftSeconds = remainingMillis / 1000

            AccessibilityBlocker.isBlocking = true
            isGlobalLocked = true
            enableDnd()
            muteAll()

            startTimer(unlockTimestamp)
        } else if (unlockTimestamp > 0 && unlockTimestamp <= currentTimestamp) {
            unlock()
        }
    }

    fun startLock(minutes: Int) {
        if (minutes <= 0) return
        val context = getApplication<Application>()

        if (!saveOriginalLauncherToPrefs()) {
            Toast.makeText(context, "无法识别原桌面，请先手动设置默认桌面", Toast.LENGTH_LONG).show()
            return
        }

        val myPackage = context.packageName
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(0)) {
            // 锁定逻辑：setDeviceLauncher 是最稳的
            ShizukuHelper.setDeviceLauncher(myPackage, "$myPackage.MainActivity")

            val unlockTimestamp = System.currentTimeMillis() + (minutes * 60 * 1000)
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_UNLOCK_TIMESTAMP, unlockTimestamp).apply()

            isLocked = true
            AccessibilityBlocker.isBlocking = true
            isGlobalLocked = true
            muteAll()
            enableNotificationListener()
            enableDnd()

            startTimer(unlockTimestamp)
            Toast.makeText(context, "Locked!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "请先连接 Shizuku", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTimer(endTime: Long) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val current = System.currentTimeMillis()
                val remainingMillis = endTime - current
                if (remainingMillis <= 0) {
                    timeLeftSeconds = 0
                    unlock()
                    break
                }
                timeLeftSeconds = remainingMillis / 1000
                delay(500)
            }
        }
    }

    fun unlock() {
        isLocked = false
        AccessibilityBlocker.isBlocking = false
        isGlobalLocked = false

        disableDnd()
        unmuteAll()

        timerJob?.cancel()

        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_UNLOCK_TIMESTAMP).apply()

        val originalPkg = prefs.getString(KEY_PKG, null)
        val originalCls = prefs.getString(KEY_CLS, null)

        viewModelScope.launch {
            if (originalPkg != null && originalCls != null) {
                val myPackage = context.packageName

                // 1. 清除 Locker 霸权
                ShizukuHelper.clearPreferredActivities(myPackage)

                // 2. 尝试 A 计划 (Role)
                val resRole = ShizukuHelper.runShellCommand("cmd role add-role-holder --user 0 android.app.role.HOME $originalPkg")

                if (resRole.exitCode == 0) {
                    // A 计划成功
                    ShizukuHelper.forceStartHome(originalPkg, originalCls)
                    Toast.makeText(context, "恢复成功 (Role)", Toast.LENGTH_SHORT).show()
                } else {
                    // 3. A 计划失败，尝试 B 计划 (Set Home Activity)
                    // 注意：这里调用的是 ShizukuHelper 里修正过带 --user 0 的版本
                    val resPkg = ShizukuHelper.runShellCommand("cmd package set-home-activity --user 0 $originalPkg/$originalCls")

                    if (resPkg.exitCode == 0) {
                        // B 计划成功
                        ShizukuHelper.forceStartHome(originalPkg, originalCls)
                        Toast.makeText(context, "恢复成功 (Pkg)", Toast.LENGTH_SHORT).show()
                    } else {
                        // 4. 【兜底】C 计划：都失败了，跳转系统设置
                        // 既然脚本无权修改，就带用户去“默认应用设置”页，让他手动点一下
                        Toast.makeText(context, "自动恢复失败，请手动选择桌面", Toast.LENGTH_LONG).show()

                        try {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // 极少数系统可能没有这个 Intent，尝试跳主设置页
                            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                }
            } else {
                Toast.makeText(context, "未找到原桌面记录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun dismissDialog() {
        showDebugDialog = false
    }

    // --- 辅助功能 ---

    private fun saveOriginalLauncherToPrefs(): Boolean {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

        if (resolveInfo != null) {
            val pkg = resolveInfo.activityInfo.packageName
            val cls = resolveInfo.activityInfo.name

            if (pkg != context.packageName) {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_PKG, pkg)
                    .putString(KEY_CLS, cls)
                    .apply()
                return true
            } else {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                return prefs.contains(KEY_PKG)
            }
        }
        return false
    }

    // ... (Volume / Notification Listener / DND 代码保持不变) ...
    private fun enableNotificationListener() {
        val myPackage = getApplication<Application>().packageName
        val componentName = "$myPackage/${NotificationBlocker::class.java.name}"
        val cmd = "cmd notification allow_listener $componentName"
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(0)) {
            ShizukuHelper.runShellCommand(cmd)
        }
    }
    fun bringAppToFront() {
        // 只有在锁定状态下才执行，防止误伤
        if (isLocked) {
            val context = getApplication<Application>()
            val myPackage = context.packageName

            // 再次确保我们是默认桌面 (防止被恶意修改)
            if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(0)) {
                ShizukuHelper.setDeviceLauncher(myPackage, "$myPackage.MainActivity")
                // 强行启动自己
                ShizukuHelper.forceStartHome(myPackage, "$myPackage.MainActivity")
            }
        }
    }
    private fun muteAll() {
        val context = getApplication<Application>()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val streams = listOf(AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)

        streams.forEach { stream ->
            val currentVol = am.getStreamVolume(stream)
            if (!prefs.contains(KEY_VOL_PREFIX + stream)) {
                editor.putInt(KEY_VOL_PREFIX + stream, currentVol)
            }

            // [修复] 加上 try-catch 防止 DND 模式下崩溃
            try {
                am.setStreamVolume(stream, 0, 0)
            } catch (e: SecurityException) {
                // 如果崩了，说明系统已经在 DND 模式，无法更改音量。
                // 这其实是好事（说明已经静音了），直接忽略即可。
                e.printStackTrace()
            }
        }
        editor.apply()
    }

    private fun unmuteAll() {
        val context = getApplication<Application>()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val streams = listOf(AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_MUSIC)

        streams.forEach { stream ->
            val originalVol = prefs.getInt(KEY_VOL_PREFIX + stream, -1)
            if (originalVol != -1) {
                // [修复] 恢复音量时也要加 try-catch，防止解锁瞬间 DND 还没退出的情况
                try {
                    am.setStreamVolume(stream, originalVol, 0)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 无论是否恢复成功，都要把记录删掉，防止下次逻辑错乱
                editor.remove(KEY_VOL_PREFIX + stream)
            }
        }
        editor.apply()
    }
    private fun enableDnd() {
        val context = getApplication<Application>()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val myPackage = context.packageName
        viewModelScope.launch {
            if (!nm.isNotificationPolicyAccessGranted) {
                if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(0)) {
                    ShizukuHelper.grantNotificationPermission(myPackage)
                }
            }
            var retryCount = 0
            while (!nm.isNotificationPolicyAccessGranted && retryCount < 10) {
                delay(200)
                retryCount++
            }
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        }
    }
    private fun disableDnd() {
        val context = getApplication<Application>()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
}