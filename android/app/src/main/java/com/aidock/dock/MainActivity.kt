package com.aidock.dock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aidock.dock.ui.DockScreen
import com.aidock.dock.ui.DockTheme

class MainActivity : ComponentActivity() {
    private val vm: DockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 책상 위 전용 화면: 꺼지지 않게, 상태바/내비게이션바 숨김
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            DockTheme {
                DockScreen(
                    vm,
                    onPickImage = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onNightMode = ::setNightBrightness,
                    onExit = { finishAndRemoveTask() },
                )
            }
        }
    }

    /**
     * 취침 모드에서는 이 앱 화면의 밝기만 낮춥니다(시스템 밝기 설정은 그대로).
     * 1%는 시계가 너무 안 보여서 10%로 둡니다.
     */
    private fun setNightBrightness(night: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (night) NIGHT_BRIGHTNESS
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    companion object {
        const val NIGHT_BRIGHTNESS = 0.10f
    }

    // 배경으로 쓸 사진 고르기 (사진 선택기가 없는 기기에서는 파일 선택 창으로 대신 열림)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setDeviceBackground(uri)
    }
}
