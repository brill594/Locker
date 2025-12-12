package com.brill.locker

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationBlocker : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 核心逻辑：检测到有新通知发出来

        // 只有当“全局锁定状态”为 true 时才动手
        // 这里的 LockViewModel.isGlobalLocked 需要我们在 ViewModel 里定义一下 Companion Object
        if (LockViewModel.isGlobalLocked) {
            // 杀无赦！
            // cancelNotification(sbn.key) // 只杀当前这个
            cancelAllNotifications() // 或者更狠一点，把队列里的全清空
        }
    }
}