package com.minibrowser

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.minibrowser.data.ThemeMode
import com.minibrowser.ui.BrowserDialogs
import com.minibrowser.ui.BrowserScreen
import com.minibrowser.ui.BrowserTheme
import com.minibrowser.ui.BrowserUiState
import com.minibrowser.ui.BookmarksScreen
import com.minibrowser.ui.CookieManagerScreen
import com.minibrowser.ui.DownloadsScreen
import com.minibrowser.ui.ExposureScreen
import com.minibrowser.ui.FilterListsScreen
import com.minibrowser.ui.HistoryScreen
import com.minibrowser.ui.MiniBrowserTheme
import com.minibrowser.ui.PrivacySettingsScreen
import com.minibrowser.ui.PrivateInfoScreen
import com.minibrowser.ui.SettingsScreen
import com.minibrowser.ui.SitePermissionsScreen
import com.minibrowser.ui.TabSwitcherScreen

class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()

    private var pendingFileResult: ((Int, Intent?) -> Unit)? = null
    private var pendingPermissionResult: ((Map<String, Boolean>) -> Unit)? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = pendingFileResult
        pendingFileResult = null
        cb?.invoke(r.resultCode, r.data)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val cb = pendingPermissionResult
        pendingPermissionResult = null
        cb?.invoke(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Прозорі системні панелі: верхню/нижню безпечні зони враховує Compose через WindowInsets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        vm.engine.attachActivity(this)
        vm.fileChooserLauncher = { intent, cb ->
            try {
                pendingFileResult = cb
                fileLauncher.launch(intent)
                true
            } catch (e: Exception) {
                pendingFileResult = null
                false
            }
        }
        vm.permissionRequester = { perms, cb ->
            pendingPermissionResult?.invoke(perms.associateWith { false })
            pendingPermissionResult = cb
            try {
                permissionLauncher.launch(perms.toTypedArray())
            } catch (e: Exception) {
                pendingPermissionResult = null
                cb(perms.associateWith { false })
            }
        }
        vm.externalStarter = { intent ->
            try {
                startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }

        if (savedInstanceState == null) vm.handleIntent(intent)

        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.engine.attachActivity(this)
        vm.onActivityResume()
    }

    override fun onPause() {
        vm.onActivityPause()
        super.onPause()
    }

    override fun onDestroy() {
        vm.fileChooserLauncher = null
        vm.permissionRequester = null
        vm.externalStarter = null
        vm.onActivityDestroy(this, isFinishing)
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------ UI

    @Composable
    private fun Root() {
        val settings by vm.settings.state.collectAsState()
        val activeTab by vm.activeTab.collectAsState()
        val ready by vm.ready.collectAsState()
        val systemDark = isSystemInDarkTheme()
        val dark = when (settings.theme) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        val isPrivate = activeTab?.isPrivate == true

        MiniBrowserTheme(dark = dark, isPrivate = isPrivate) {
            val c = BrowserTheme.colors
            val ui = remember { BrowserUiState() }
            val stack by vm.stack.collectAsState()
            val fullscreen by vm.fullscreen.collectAsState()
            val customView by vm.customView.collectAsState()
            val focusManager = LocalFocusManager.current

            // Значки системних панелей відповідають темі.
            SideEffect {
                val ctl = WindowCompat.getInsetsController(window, window.decorView)
                ctl.isAppearanceLightStatusBars = !c.isDark
                ctl.isAppearanceLightNavigationBars = !c.isDark
            }

            // Повноекранний режим сторінки або відео ховає системні панелі.
            val immersive = (fullscreen && stack.isEmpty()) || customView != null
            LaunchedEffect(immersive) {
                val ctl = WindowCompat.getInsetsController(window, window.decorView)
                if (immersive) {
                    ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    ctl.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    ctl.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            // Приватні вкладки: за бажанням забороняємо знімки екрана та прев'ю в «Недавніх».
            val secure = isPrivate && settings.blockScreenshotsInPrivate
            LaunchedEffect(secure) {
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }

            LaunchedEffect(Unit) {
                vm.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            }

            // Єдиний обробник «Назад»: від найвищого шару до найнижчого.
            BackHandler {
                when {
                    customView != null -> vm.hideCustomView()
                    ui.showDashboard -> ui.showDashboard = false
                    ui.showMenu -> ui.showMenu = false
                    ui.addressFocused -> focusManager.clearFocus()
                    stack.isNotEmpty() -> vm.closeScreen()
                    fullscreen -> vm.exitFullscreen()
                    vm.goBack() -> Unit
                    else -> finish()
                }
            }

            Box(Modifier.fillMaxSize().background(c.bg)) {
                if (ready) {
                    BrowserScreen(vm, ui)

                    stack.forEach { screen ->
                        key(screen) {
                            val state = remember { MutableTransitionState(false).apply { targetState = true } }
                            AnimatedVisibility(
                                visibleState = state,
                                enter = fadeIn() + slideInHorizontally { it / 6 },
                                exit = fadeOut(),
                            ) {
                                when (screen) {
                                    Screen.TABS -> TabSwitcherScreen(vm)
                                    Screen.HISTORY -> HistoryScreen(vm)
                                    Screen.BOOKMARKS -> BookmarksScreen(vm)
                                    Screen.DOWNLOADS -> DownloadsScreen(vm)
                                    Screen.SETTINGS -> SettingsScreen(vm)
                                    Screen.PRIVACY -> PrivacySettingsScreen(vm)
                                    Screen.COOKIES -> CookieManagerScreen(vm)
                                    Screen.PERMISSIONS -> SitePermissionsScreen(vm)
                                    Screen.FILTERS -> FilterListsScreen(vm)
                                    Screen.PRIVATE_INFO -> PrivateInfoScreen(vm)
                                    Screen.EXPOSURE -> ExposureScreen(vm)
                                }
                            }
                        }
                    }

                    BrowserDialogs(vm)

                    // Повноекранне відео з <video>: власний вигляд WebView поверх усього.
                    customView?.let { cv ->
                        key(cv) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize().background(Color.Black),
                                factory = { ctx ->
                                    FrameLayout(ctx).apply {
                                        setBackgroundColor(AndroidColor.BLACK)
                                        (cv.view.parent as? android.view.ViewGroup)?.removeView(cv.view)
                                        addView(
                                            cv.view,
                                            FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                            ),
                                        )
                                    }
                                },
                                onRelease = { (it as? FrameLayout)?.removeAllViews() },
                            )
                        }
                    }
                }
            }
        }
    }
}
