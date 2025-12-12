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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

// 定义主题颜色
val ColorBackground = Color(0xFFFFF0F5) // 极淡的樱花粉背景
val ColorMainPink = Color(0xFFFF8DA1)   // 主题桃粉色 (标题、按钮)
val ColorLightPink = Color(0xFFFFB7C5)  // 浅粉色 (次要按钮)
val ColorTextPrimary = Color(0xFF5D4037) // 深咖色 (正文文本，比纯黑柔和)
val ColorTextSecondary = Color(0xFF9E8489) // 灰粉色 (次要文本)

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: LockViewModel

    // Fix for Shizuku listener memory leak warning (Implicit SAM conversion)
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuListener)

        setContent {
            MaterialTheme {
                // 修改背景颜色为粉白
                Surface(modifier = Modifier.fillMaxSize(), color = ColorBackground) {
                    val vm: LockViewModel = viewModel()
                    viewModel = vm

                    DisposableEffect(Unit) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP) {
                                if (vm.isLocked) {
                                    vm.bringAppToFront()
                                }
                            }
                        }
                        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
                        onDispose {
                            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
                        }
                    }

                    AppContent(vm)
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
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
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
        containerColor = Color.White, // 弹窗背景改为纯白
        titleContentColor = ColorTextPrimary, // FIXED: Correct parameter name (was titleColor)
        title = { Text("执行错误 (Exit Code 255)") },
        text = {
            SelectionContainer {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = text, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = ColorMainPink)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Error Log", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ColorMainPink)
            ) { Text("复制日志", color = Color.White) }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ColorLightPink)
            ) { Text("关闭", color = Color.White) }
        }
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
            color = ColorMainPink, // 标题改为桃粉色
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
            Text(text = "MIN", color = ColorMainPink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(60.dp))

        Button(
            // 按钮颜色改为桃粉色
            colors = ButtonDefaults.buttonColors(containerColor = ColorMainPink),
            onClick = { onStart(selectedMinutes) },
            modifier = Modifier.height(50.dp).width(150.dp)
        ) {
            Text("START", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (!ShizukuHelper.isShizukuAvailable()) {
            Text("Shizuku 未连接", color = ColorTextSecondary, fontSize = 12.sp)
        } else if (!ShizukuHelper.checkPermission(0)) {
            Button(
                onClick = { ShizukuHelper.requestPermission(0) },
                // 授权按钮改为浅粉色
                colors = ButtonDefaults.buttonColors(containerColor = ColorLightPink)
            ) {
                Text("授权 Shizuku", fontSize = 12.sp, color = Color.White)
            }
        }

        if (!isAccessibilityReady) {
            Button(
                onClick = { },
                // 按钮改为浅粉色
                colors = ButtonDefaults.buttonColors(containerColor = ColorLightPink),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                val context = LocalContext.current
                Text("开启无障碍服务", fontSize = 12.sp, color = Color.White, modifier = Modifier.clickable {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                })
            }
        } else {
            // "已就绪" 改为桃粉色
            Text("已就绪", color = ColorMainPink, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }

        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(20.dp))
            val context = LocalContext.current
            Button(
                onClick = { (context as? MainActivity)?.sendTestNotification() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81D4FA)) // Debug 按钮改成淡蓝色区分
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
        // 锁机界面背景也改为粉白
        modifier = Modifier.fillMaxSize().background(ColorBackground),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "$mm : $ss", color = ColorMainPink, fontSize = 80.sp, fontWeight = FontWeight.Bold)
        Text(text = "FOCUS MODE", color = ColorTextSecondary, fontSize = 16.sp, letterSpacing = 4.sp)

        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(50.dp))
            Button(
                onClick = onEmergency,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text("[DEBUG] Emergency Unlock", color = ColorLightPink, fontSize = 12.sp)
            }

            val context = LocalContext.current
            Button(
                onClick = { (context as? MainActivity)?.sendTestNotification() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81D4FA))
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
        // 滚轮中间的选中条：改为半透明的粉色，加圆角
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    color = ColorMainPink.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                )
        )
        LazyColumn(
            state = listState,
            flingBehavior = snapBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            item { Spacer(modifier = Modifier.height((height - itemHeight) / 2)) }
            items(range.count()) { index ->
                // 使用 Box 包裹 Text 以确保垂直居中
                Box(
                    modifier = Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (range.first + index).toString(),
                        // 滚轮数字颜色：改为深咖色，比纯黑更柔和
                        color = ColorTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
            item { Spacer(modifier = Modifier.height((height - itemHeight) / 2)) }
        }
    }
}