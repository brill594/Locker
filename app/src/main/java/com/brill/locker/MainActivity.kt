package com.brill.locker

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.brill.locker.BuildConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addRequestPermissionResultListener { _, _ -> }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val viewModel: LockViewModel = viewModel()
                    AppContent(viewModel)
                }
            }
        }
    }

    internal fun sendTestNotification() {
        val channelId = "test_channel"
        val nm = getSystemService(android.app.NotificationManager::class.java)

        if (nm.getNotificationChannel(channelId) == null) {
            val channel = android.app.NotificationChannel(
                channelId,
                "测试噪音通道",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Debug测试"
                enableVibration(true)
                enableLights(true)
            }
            nm.createNotificationChannel(channel)
        }

        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Debug测试")
            .setContentText("这是一条测试通知")
            .setAutoCancel(true)
            .build()

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

    if (viewModel.showDebugDialog) {
        DebugDialog(
            text = viewModel.debugOutput,
            onDismiss = { viewModel.dismissDialog() }
        )
    }

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
fun DebugDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("执行错误 (Exit Code 255)") },
        text = {
            SelectionContainer {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = text, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color.Red)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("Error Log", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制日志") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("关闭") } }
    )
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

        if (!isAccessibilityReady) {
            Button(
                onClick = { },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                val context = LocalContext.current
                Text("开启无障碍服务", fontSize = 12.sp, color = Color.White, modifier = Modifier.clickable {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                })
            }
        } else {
            Text("已就绪", color = Color.Green, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }

        // ⬇️⬇️⬇️ 只有在 Debug 版本才会显示这个按钮 ⬇️⬇️⬇️
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(20.dp))
            val context = LocalContext.current
            Button(
                onClick = { (context as? MainActivity)?.sendTestNotification() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
            ) {
                Text("[DEBUG] 测试通知", color = Color.White)
            }
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

        // ⬇️⬇️⬇️ 只有在 Debug 版本才会显示“紧急解锁” ⬇️⬇️⬇️
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(50.dp))
            Button(
                onClick = onEmergency,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text("[DEBUG] Emergency Unlock", color = Color.Red, fontSize = 12.sp)
            }

            // 调试通知按钮
            val context = LocalContext.current
            Button(
                onClick = { (context as? MainActivity)?.sendTestNotification() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
            ) {
                Text("[DEBUG] 测试通知", color = Color.White)
            }
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