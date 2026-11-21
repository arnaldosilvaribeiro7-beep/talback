package com.example.data.remote

import com.example.data.model.LinkPreviewMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LinkPreviewFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val urlPattern = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )

    fun extractFirstUrl(text: String): String? {
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    suspend fun fetchMetadata(url: String): LinkPreviewMetadata? = withContext(Dispatchers.IO) {
        try {
            val uri = URI(url)
            val host = (uri.host ?: "").lowercase().removePrefix("www.")
            val platform = when {
                host.contains("youtube.com") || host.contains("youtu.be") -> "YouTube"
                host.contains("vimeo.com") -> "Vimeo"
                host.contains("twitch.tv") -> "Twitch"
                host.contains("tiktok.com") -> "TikTok"
                host.contains("instagram.com") -> "Instagram"
                host.contains("x.com") || host.contains("twitter.com") -> "X / Twitter"
                else -> host.ifBlank { "Link externo" }
            }

            val lowerPath = (uri.path ?: "").lowercase()
            val isDirectMedia = lowerPath.endsWith(".mp4") ||
                lowerPath.endsWith(".webm") ||
                lowerPath.endsWith(".m3u8")

            if (isDirectMedia) {
                return@withContext LinkPreviewMetadata(
                    url = url,
                    title = uri.path?.substringAfterLast('/')?.ifBlank { "Vídeo incorporado" } ?: "Vídeo incorporado",
                    platform = platform,
                    description = "Reprodução direta suportada no player nativo do Talkback.",
                    thumbnailUrl = null,
                    isDirectPlayableMedia = true
                )
            }

            // YouTube fast thumbnail fallback + OpenGraph extraction
            val ytId = extractYoutubeId(url)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) TalkbackBot/1.0")
                .build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "" else response.body?.string()?.take(65536) ?: ""
            }

            val ogTitle = extractMeta(html, "og:title")
                ?: extractHtmlTitle(html)
                ?: if (ytId != null) "Vídeo do YouTube" else platform
            val ogDesc = extractMeta(html, "og:description")
                ?: extractMeta(html, "description")
                ?: url
            val ogImage = extractMeta(html, "og:image")
                ?: ytId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }

            LinkPreviewMetadata(
                url = url,
                title = ogTitle.take(200),
                platform = platform.take(80),
                description = ogDesc.take(300),
                thumbnailUrl = ogImage?.take(500),
                isDirectPlayableMedia = false
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("youtu\\.be/([a-zA-Z0-9_-]{6,})"),
            Regex("[?&]v=([a-zA-Z0-9_-]{6,})"),
            Regex("/shorts/([a-zA-Z0-9_-]{6,})")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.getOrNull(1) }
    }

    private fun extractMeta(html: String, propertyName: String): String? {
        if (html.isBlank()) return null
        val propRegex = Regex(
            """<meta[^>]+(?:property|name)=["']${Regex.escape(propertyName)}["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        propRegex.find(html)?.groupValues?.getOrNull(1)?.let { return decodeHtml(it) }
        val revRegex = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${Regex.escape(propertyName)}["']""",
            RegexOption.IGNORE_CASE
        )
        return revRegex.find(html)?.groupValues?.getOrNull(1)?.let { decodeHtml(it) }
    }

    private fun extractHtmlTitle(html: String): String? {
        val match = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.getOrNull(1)?.trim()?.let { decodeHtml(it) }
    }

    private fun decodeHtml(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()
}
