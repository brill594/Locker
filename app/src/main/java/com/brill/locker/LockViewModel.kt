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

        // 1. 保存原来的桌面
        if (!saveOriginalLauncherToPrefs()) {
            Toast.makeText(context, "无法识别原桌面", Toast.LENGTH_LONG).show()
            return
        }

        val myPackage = context.packageName
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(0)) {
            // 2. 将 Locker 设为默认桌面 (双重命令)
            // 先用 RoleManager (新)
            ShizukuHelper.setHomeRole(myPackage)
            // 再用 PreferredActivity (旧) 做兼容
            ShizukuHelper.setDeviceLauncher(myPackage, "$myPackage.MainActivity")

            // 3. 写入时间
            val unlockTimestamp = System.currentTimeMillis() + (minutes * 60 * 1000)
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_UNLOCK_TIMESTAMP, unlockTimestamp).apply()

            // 4. 开启封锁
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

        // --- 核心恢复逻辑 ---
        val originalPkg = prefs.getString(KEY_PKG, null)
        val originalCls = prefs.getString(KEY_CLS, null)

        viewModelScope.launch {
            if (originalPkg != null && originalCls != null) {
                // 1. 归还名分 (Role + Activity)
                // 先给 Role (Android 15 认这个)
                // 再设 Default (双保险)
                ShizukuHelper.setDeviceLauncher(originalPkg, originalCls)
                ShizukuHelper.setHomeRole(originalPkg)
                // 2. 扶持上位 (AM START)
                ShizukuHelper.forceStartHome(originalPkg, originalCls)

                Toast.makeText(context, "已恢复: $originalPkg", Toast.LENGTH_SHORT).show()
            } else {
                // 兜底：恢复微软桌面
                val msPkg = "com.android.launcher"
                val msCls = "com.android.launcher.Launcher"
                ShizukuHelper.setHomeRole(msPkg)
                ShizukuHelper.setDeviceLauncher(msPkg, msCls)
                ShizukuHelper.forceStartHome(msPkg, msCls)
                Toast.makeText(context, "执行兜底恢复", Toast.LENGTH_SHORT).show()
            }
            // 彻底移除 forceReleaseLauncher (禁用自己)
        }
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
                // 已经是 Locker 了，信任硬盘里的旧数据
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                return prefs.contains(KEY_PKG)
            }
        }
        return false
    }

    // ... (Notification Listener / DND / Volume 代码保持不变) ...
    private fun enableNotificationListener() {
        val myPackage = getApplication<Application>().packageName
        val componentName = "$myPackage/${NotificationBlocker::class.java.name}"
        val cmd = "cmd notification allow_listener $componentName"
        if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.checkPermission(0)) {
            ShizukuHelper.runShellCommand(cmd)
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
            am.setStreamVolume(stream, 0, 0)
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
                try { am.setStreamVolume(stream, originalVol, 0) } catch (e: Exception) {}
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