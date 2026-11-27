package com.minibrowser.privacy

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Захист від надмірного збору характеристик пристрою — у межах можливостей WebView.
 *
 * Що робиться:
 *  1. Відомі fingerprinting-сервіси блокуються списками (див. fingerprinting.txt та EasyPrivacy).
 *  2. На початку кожного документа вставляється спостерігач, який ЛИШЕ повідомляє застосунку, що
 *     сторінка звернулась до canvas / WebGL (debug renderer) / OfflineAudioContext /
 *     enumerateDevices / getBattery. Він викликає оригінальну функцію з тими самими аргументами
 *     і повертає реальний результат — значення НЕ підміняються.
 *  3. Користувач бачить такі спроби в панелі приватності.
 *
 * Чого НЕ робиться (за вимогами): User-Agent, часовий пояс, мова, розмір екрана, видимість
 * вкладки, focus/blur, visibilitychange та інші браузерні сигнали не змінюються й не маскуються.
 *
 * Спостерігач повідомляє тільки короткі рядки через WebMessageListener (без JavascriptInterface),
 * тому сторінка не отримує жодного доступу до Android-функцій.
 */
class FingerprintProtection(private val dashboard: PrivacyDashboard) {

    val isSupported: Boolean
        get() = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        } catch (e: Exception) {
            false
        }

    private val handlers = HashMap<WebView, ScriptHandler>()

    /** Викликається один раз для нового WebView, до першого завантаження. */
    fun install(webView: WebView, tabId: String, enabled: Boolean) {
        if (!isSupported) return
        try {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                setOf("*"),
                WebViewCompat.WebMessageListener { _, message, sourceOrigin, _, _ ->
                    val kind = message.data
                    if (kind != null && kind in KINDS) {
                        dashboard.recordFingerprint(tabId, kind, sourceOrigin.host)
                    }
                },
            )
        } catch (e: Exception) {
            return
        }
        setEnabled(webView, enabled)
    }

    fun setEnabled(webView: WebView, enabled: Boolean) {
        if (!isSupported) return
        try {
            val existing = handlers[webView]
            if (enabled && existing == null) {
                handlers[webView] = WebViewCompat.addDocumentStartJavaScript(webView, OBSERVER_SCRIPT, setOf("*"))
            } else if (!enabled && existing != null) {
                existing.remove()
                handlers.remove(webView)
            }
        } catch (_: Exception) {
        }
    }

    fun forget(webView: WebView) {
        handlers.remove(webView)
    }

    companion object {
        const val BRIDGE_NAME = "__mbFp"
        val KINDS = setOf("canvas", "webgl", "audio", "devices", "battery")

        fun describe(kind: String): String = when (kind) {
            "canvas" -> "Читання пікселів canvas (часто використовується для fingerprinting)"
            "webgl" -> "Запит моделі відеокарти через WebGL"
            "audio" -> "Аудіо-обробка OfflineAudioContext (audio fingerprint)"
            "devices" -> "Перелік мультимедійних пристроїв"
            "battery" -> "Читання стану батареї"
            else -> kind
        }

        private val OBSERVER_SCRIPT = """
(function () {
  if (window.__mbFpInstalled) return;
  window.__mbFpInstalled = true;
  var reported = {};
  function report(kind) {
    if (reported[kind]) return;
    reported[kind] = true;
    try { __mbFp.postMessage(kind); } catch (e) {}
  }
  function wrap(proto, name, kind, filter) {
    try {
      if (!proto) return;
      var orig = proto[name];
      if (typeof orig !== 'function') return;
      proto[name] = function () {
        try { if (!filter || filter(arguments)) report(kind); } catch (e) {}
        return orig.apply(this, arguments);
      };
    } catch (e) {}
  }
  wrap(window.HTMLCanvasElement && HTMLCanvasElement.prototype, 'toDataURL', 'canvas');
  wrap(window.HTMLCanvasElement && HTMLCanvasElement.prototype, 'toBlob', 'canvas');
  wrap(window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype, 'getImageData', 'canvas');
  var dbg = function (a) { return a[0] === 'WEBGL_debug_renderer_info'; };
  wrap(window.WebGLRenderingContext && WebGLRenderingContext.prototype, 'getExtension', 'webgl', dbg);
  wrap(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype, 'getExtension', 'webgl', dbg);
  wrap(window.OfflineAudioContext && OfflineAudioContext.prototype, 'startRendering', 'audio');
  wrap(window.MediaDevices && MediaDevices.prototype, 'enumerateDevices', 'devices');
  wrap(window.Navigator && Navigator.prototype, 'getBattery', 'battery');
})();
""".trimIndent()
    }
}
