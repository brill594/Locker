package com.brill.locker // 确认包名是小写

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener { _, _ -> }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    // 获取 ViewModel
                    val viewModel: LockViewModel = viewModel()
                    // 传递给 UI
                    AppContent(viewModel)
                }
            }
        }
    }
    internal fun sendTestNotification() {
        val channelId = "test_channel"
        val nm = getSystemService(android.app.NotificationManager::class.java)

        // 1. 创建一个“极度吵闹”的通知渠道 (Android 8.0+)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = android.app.NotificationChannel(
                channelId,
                "测试噪音通道",
                android.app.NotificationManager.IMPORTANCE_HIGH // 高优先级：会有声音和悬浮窗
            ).apply {
                description = "专门用来测试勿扰模式是否生效"
                enableVibration(true) // 开启震动
                enableLights(true)
            }
            nm.createNotificationChannel(channel)
        }

        // 2. 构建通知
        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // 系统自带图标
            .setContentTitle("勿扰模式测试")
            .setContentText("如果你听到了声音或看到了弹窗，说明勿扰失败了！")
            .setAutoCancel(true)
            .build()

        // 3. 发射！
        nm.notify(999, notification)
    }
    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener { _, _ -> }
    }
}

@Composable
fun AppContent(viewModel: LockViewModel) {
    val isLocked = viewModel.isLocked
    val timeLeftSeconds = viewModel.timeLeftSeconds
    val isAccessibilityReady = viewModel.isAccessibilityReady

    if (!isLocked) {
        SetupScreen(
            onStart = { minutes -> viewModel.startLock(minutes) },
            isAccessibilityReady = isAccessibilityReady
        )
    } else {
        LockScreen(
            seconds = timeLeftSeconds,
            onEmergency = { viewModel.unlock() }
        )
    }
}

@Composable
fun SetupScreen(onStart: (Int) -> Unit, isAccessibilityReady: Boolean) {
    var selectedMinutes by remember { mutableIntStateOf(10) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "LOCKER",
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 45.sp
        )

        Spacer(modifier = Modifier.height(60.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(
                range = 1..120,
                initialValue = 10,
                onValueChange = { selectedMinutes = it }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "MIN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(60.dp))

        Button(
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD71921)),
            onClick = { onStart(selectedMinutes) },
            modifier = Modifier.height(50.dp).width(150.dp)
        ) {
            Text("START", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 权限检查区域
        if (!ShizukuHelper.isShizukuAvailable()) {
            Text("Shizuku 未连接", color = Color.Gray, fontSize = 12.sp)
        } else if (!ShizukuHelper.checkPermission(0)) {
            Button(
                onClick = { ShizukuHelper.requestPermission(0) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("授权 Shizuku", fontSize = 12.sp, color = Color.White)
            }
        }

        // 无障碍检查区域
        if (!isAccessibilityReady) {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    // 需要 Context 启动 Activity，这里简化处理，实际可以使用 LocalContext.current
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                // 注意：LocalContext.current 需要在 Composable 内部获取
                val context = LocalContext.current
                Text("开启无障碍服务", fontSize = 12.sp, color = Color.White, modifier = Modifier.clickable {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                })
            }
        } else {
            Text("已就绪", color = Color.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(modifier = Modifier.height(20.dp))

        // 测试按钮
        val context = LocalContext.current
        Button(
            onClick = {
                // 调用 MainActivity 的方法
                (context as? MainActivity)?.sendTestNotification()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
        ) {
            Text("发送高亮通知测试", color = Color.White)
        }
    }
}

@Composable
fun LockScreen(seconds: Long, onEmergency: () -> Unit) {
    val mm = (seconds / 60).toString().padStart(2, '0')
    val ss = (seconds % 60).toString().padStart(2, '0')

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "$mm : $ss", color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Bold)
        Text(text = "FOCUS MODE", color = Color.Gray, fontSize = 16.sp, letterSpacing = 4.sp)
        Spacer(modifier = Modifier.height(100.dp))
        Button(
            onClick = onEmergency,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text("Emergency Unlock", color = Color.DarkGray, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(20.dp))

        // 测试按钮
        val context = LocalContext.current
        Button(
            onClick = {
                // 调用 MainActivity 的方法
                (context as? MainActivity)?.sendTestNotification()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
        ) {
            Text("发送高亮通知测试", color = Color.White)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(range: IntRange, initialValue: Int, onValueChange: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialValue - range.first)
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val height = 100.dp
    val itemHeight = 40.dp

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerIndex = listState.firstVisibleItemIndex
            val value = range.first + centerIndex
            onValueChange(value)
        }
    }

    Box(modifier = Modifier.height(height).width(80.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxWidth().height(itemHeight).background(Color.DarkGray.copy(alpha = 0.3f)))
        LazyColumn(
            state = listState,
            flingBehavior = snapBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { Spacer(modifier = Modifier.height((height - itemHeight) / 2)) }
            items(range.count()) { index ->
                Text(
                    text = (range.first + index).toString(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.height(itemHeight),
                    textAlign = TextAlign.Center
                )
            }
            item { Spacer(modifier = Modifier.height((height - itemHeight) / 2)) }
        }
    }
}