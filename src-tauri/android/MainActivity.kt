package com.hghub.huangguo

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 由 `pnpm tauri android init --ci` 生成的 MainActivity（Tauri 2.12.5 模板），
 * 加上安全区桥接。入库副本在 src-tauri/android/MainActivity.kt，CI 在 init
 * 之后把它拷进 gen/android（见 .github/workflows/release.yml），直接改
 * gen/ 下的文件活不过下一次 init。
 *
 * 桥接原因：应用沉浸式绘制（enableEdgeToEdge），但 Android 的 WebView 从不
 * 经 CSS env(safe-area-inset-*) 暴露系统栏/刘海 inset（iOS 会），前端的
 * --sab/--sat 会归 0，底部标签栏就画到三键导航栏下面、点不到。这里在每次
 * inset 变化时把真实值（CSS px）推给前端的 --sat-native / --sab-native，
 * 前端优先用它们，env() 只作兜底；桌面与 iOS 不受影响。
 */
class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // 挂在 content view（不是 decor view）上，不动窗口自身的 inset 处理；
    // 原样返回 insets，不消费，让它们继续传给子 view（WebView）。
    ViewCompat.setOnApplyWindowInsetsListener(
      findViewById<View>(android.R.id.content)
    ) { _, insets ->
      applySafeAreaToWebView(insets)
      insets
    }
  }

  /**
   * 重新分发当前 window insets。Rust 的 `sync_safe_area` 命令在 web 应用
   * 启动后调一次：刚加载的文档从 CSS 默认值（0）起步，而上面的 listener
   * 只在 inset 本身变化时才再触发。
   */
  fun syncSafeArea() {
    runOnUiThread { findViewById<View>(android.R.id.content).requestApplyInsets() }
  }

  /**
   * 把系统栏 + 刘海 inset 推给前端的 --sat-native / --sab-native，同时给
   * WebView 加底部原生 padding（空白区），把底部标签栏顶到三键导航栏之上。
   *
   * 为什么是原生 padding 而不是前端垫空白：只有原生知道导航栏的真实高度；
   * 前端垫要么写死高度（换设备不准），要么等桥接的 --sab-native（正是坏
   * 掉的那环）。padding 不依赖页面加载时序：listener 一跑就生效。
   *
   * 动态跟随：listener 每次 inset 变化都会再跑，全屏播放时导航栏隐藏 →
   * bottom 归 0 → padding 归 0，不留空边。padding 用物理 px（bars.bottom
   * 本来就是物理 px，别除以 density）。空白区颜色跟 TabBar 走（深色
   * #141414 / 浅色 #FAFAFA，按系统夜间模式），否则默认白底在深色模式下
   * 很扎眼。--sat-native / --sab-native 的 JS 推送原样保留（诊断浮层还
   * 要用它验证一轮）。
   */
  private fun applySafeAreaToWebView(insets: WindowInsetsCompat) {
    val bars = insets.getInsets(
      WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )
    val webView = findWebView(findViewById(android.R.id.content))
    if (webView != null) {
      webView.setPadding(
        webView.paddingLeft, webView.paddingTop, webView.paddingRight, bars.bottom
      )
      val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
      webView.setBackgroundColor(if (night) 0xFF141414.toInt() else 0xFFFAFAFA.toInt())
    }
    // WebView 的 CSS px = 物理 px / density。
    val density = resources.displayMetrics.density
    val js = """
      (function () {
        var s = document.documentElement.style;
        s.setProperty('--sat-native', '${bars.top / density}px');
        s.setProperty('--sab-native', '${bars.bottom / density}px');
      })();
    """.trimIndent()
    webView?.evaluateJavascript(js, null)
  }

  private fun findWebView(root: View): WebView? {
    if (root is WebView) return root
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        findWebView(root.getChildAt(i))?.let { return it }
      }
    }
    return null
  }
}
