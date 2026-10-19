package app.sparrow.protocol

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one server key: the one whose SubjectPublicKeyInfo hashes (SHA-256, base64url)
 * to [pin]. The backend usually has a self-signed certificate, so the pin replaces the normal
 * CA check; the host name is not checked either, because the pin already identifies the server.
 */
class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server sent no certificate")
        if (pinOf(leaf) != pin) throw CertificateException("Server key does not match the enrolled pin")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Not used for client certificates")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun pinOf(cert: X509Certificate): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

        /** [base] with TLS trust limited to [pin]. */
        fun apply(base: OkHttpClient, pin: String): OkHttpClient {
            val tm = PinnedTrustManager(pin)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            return base.newBuilder()
                .sslSocketFactory(ctx.socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}
