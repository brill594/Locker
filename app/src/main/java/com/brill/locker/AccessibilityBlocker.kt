package com.brill.locker

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AccessibilityBlocker : AccessibilityService() {

    companion object {
        var isBlocking = false
        // [新增] 静态标记，服务活着就是 true，死了就是 false
        var isServiceConnected = false
    }

    // [新增] 服务连接成功时触发
    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
    }

    // [新增] 服务断开/关闭时触发
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isServiceConnected = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isBlocking || event == null) return
        if (event.packageName?.toString() == "com.android.systemui") {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() {
        isServiceConnected = false
    }

    // 拦截物理按键
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isBlocking) return false // 没锁定时，放行所有按键

        val action = event.action
        val keyCode = event.keyCode

        // 我们只处理按下事件，防止重复触发
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                // 拦截返回键
                KeyEvent.KEYCODE_BACK -> return true
                // 拦截最近任务键 (部分系统可能无法完全拦截，配合 Home 键劫持效果更好)
                KeyEvent.KEYCODE_APP_SWITCH -> return true
                // 拦截搜索键/语音助手键
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_VOICE_ASSIST -> return true
            }
        }
        // 如果我们拦截了 ACTION_DOWN，也要拦截 ACTION_UP，否则系统可能会处理一半
        if (action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_VOICE_ASSIST -> return true
            }
        }

        return super.onKeyEvent(event)
    }
}