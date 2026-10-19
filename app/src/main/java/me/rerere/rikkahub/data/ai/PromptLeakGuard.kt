package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Base64
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 「防止提示词泄露」的一次性信任（TOFU）指纹库。
 *
 * 以域名为键，保存该域名首次成功握手时看到的证书指纹（`sha256/<base64>` 形式）。
 * 之所以不写死指纹，是因为自定义供应商的域名由用户随意填写，无法预置。
 */
class TofuPinStore(context: Context) {
    private val prefs = context.getSharedPreferences("prompt_leak_pins", Context.MODE_PRIVATE)

    fun pins(host: String): Set<String> =
        prefs.getString(host, null).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    fun remember(host: String, pins: Set<String>) {
        if (pins.isEmpty()) return
        prefs.edit().putString(host, pins.joinToString("\n")).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/** 只拦截这些对话端点，避免影响模型列表、余额、图片等其它请求。 */
private val CHAT_PATH_HINTS = listOf("/chat/completions", "/responses", "/messages")

/**
 * 网络拦截器：对话请求走 HTTPS 时按 TOFU 校验上游证书指纹。
 * - 首次连接：记录该域名当前证书链的指纹；
 * - 之后连接：链上任一证书指纹命中已记录指纹即通过，否则中断（疑似中间人 / 代理「截胡」）。
 *
 * 内置模型的固定指纹在 [me.rerere.rikkahub.data.builtin.BuiltinProxyServer] 中单独处理。
 */
class PromptLeakGuardInterceptor(
    private val settingsStore: SettingsStore,
    private val pinStore: TofuPinStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val enabled = settingsStore.settingsFlow.value.qingshuiFeatures.preventPromptLeak
        if (!enabled || !request.url.isHttps) return chain.proceed(request)

        val path = request.url.encodedPath
        if (CHAT_PATH_HINTS.none { path.contains(it) }) return chain.proceed(request)

        val response = chain.proceed(request)
        val host = request.url.host
        val pins = response.handshake?.peerCertificates
            ?.map { it.spkiSha256Pin() }
            ?.toSet()
            .orEmpty()
        if (pins.isEmpty()) return response

        val known = pinStore.pins(host)
        if (known.isEmpty()) {
            pinStore.remember(host, pins)
            return response
        }
        if (known.intersect(pins).isEmpty()) {
            response.close()
            throw SSLPeerUnverifiedException(
                "「防止提示词泄露」：$host 的证书指纹与首次记录不一致，已中断连接" +
                    "（可能被中间人 / 代理截获）。如确认上游更换了证书，可在「清水特色功能」中关闭本项后重试。"
            )
        }
        return response
    }
}

/** 计算证书 SPKI SHA-256 指纹，格式与 OkHttp CertificatePinner 一致：`sha256/<base64>`。 */
private fun Certificate.spkiSha256Pin(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
    return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP)
}