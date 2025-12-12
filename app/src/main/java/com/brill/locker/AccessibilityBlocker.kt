package com.brill.locker

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AccessibilityBlocker : AccessibilityService() {

    companion object {
        var isBlocking = false
        var isServiceConnected = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isServiceConnected = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isBlocking || event == null) return

        // 1. 原有的：防止下拉通知栏
        if (event.packageName?.toString() == "com.android.systemui") {
            // 注意：这里不能无脑屏蔽 systemui，否则音量调节和关机菜单也会失效
            // 只有当它是“通知栏展开”等特定行为时才屏蔽，但为了简单，
            // 只要在锁定模式下，下拉栏一律弹回。
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. [新增] 防语音助手/其他App启动
        // 监听窗口状态变化
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return

            // 白名单：
            // 1. com.brill.locker (自己)
            // 2. com.android.systemui (系统界面，如音量条、关机菜单，必须放行，否则手机没法用)
            // 3. android (系统框架)
            if (pkgName != packageName &&
                pkgName != "com.android.systemui" &&
                pkgName != "android") {

                // 发现“非法入侵者”！
                // 执行回主页操作（因为 Locker 是默认桌面，所以回主页=回 Locker）
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    override fun onInterrupt() {
        isServiceConnected = false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isBlocking) return false

        val action = event.action
        val keyCode = event.keyCode

        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_ASSIST, // 屏蔽语音助手按键
                KeyEvent.KEYCODE_VOICE_ASSIST -> return true
            }
        }
        return super.onKeyEvent(event)
    }
}