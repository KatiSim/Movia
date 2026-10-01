package app.movia.android.domain.legacy

import app.movia.android.domain.playback.StreamRequestProfile
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Shared pool, platform TLS and public destinations for the original media URLs. */
object LegacyMediaHttp {
    private val base = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .dns(object : Dns {
            override fun lookup(host: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(host)
                if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
                    throw UnknownHostException("PROVIDER_PRIVATE_DESTINATION")
                }
                return addresses
            }
        }).build()

    fun client(profile: StreamRequestProfile): OkHttpClient = base.newBuilder()
        .addInterceptor { chain ->
            if (!LegacyProviderEngine.isMediaUrl(chain.request().url.toString())) throw IOException("PROVIDER_UNSAFE_URL")
            chain.proceed(chain.request())
        }
        .addNetworkInterceptor { chain ->
            // This interceptor runs for every redirect and every HLS/DASH segment.
            val original = chain.call().request().url
            val current = chain.request()
            if (!LegacyProviderEngine.isMediaUrl(current.url.toString()) ||
                (original.isHttps && !current.url.isHttps)) throw IOException("PROVIDER_UNSAFE_REDIRECT")
            val request = current.newBuilder().removeHeader("Cookie")
                .removeHeader("Authorization").removeHeader("Proxy-Authorization")
            profile.headersFor(current.url.toString()).entries.firstOrNull { it.key.equals("Cookie", true) }
                ?.let { request.header("Cookie", it.value) }
            chain.proceed(request.build())
        }.build()

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255
            if (a == 0 || a >= 224 || (a == 100 && b in 64..127) || (a == 198 && b in 18..19)) return false
        }
        return true
    }
}
