package net.candiago.rainbead

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the address typed on first launch and works out where the app starts and
 * which URLs belong to the instance (its "scope"), using the PWA manifest when present.
 */
object InstanceProbe {

    sealed interface Result {
        data class Ok(val start: String, val scope: String) : Result
        data class HttpError(val code: Int) : Result
        data object Unreachable : Result
    }

    private class Page(val url: String, val code: Int, val body: String)

    private val manifestLink = Regex("""<link\b[^>]*\brel\s*=\s*["']?manifest\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val hrefAttr = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun probe(address: String, userAgent: String): Result {
        val page = fetch(address, userAgent) ?: return Result.Unreachable
        if (page.code >= 400 && page.code != 401 && page.code != 403) return Result.HttpError(page.code)

        val href = manifestLink.find(page.body)?.value?.let { hrefAttr.find(it)?.groupValues?.get(1) }
        if (href != null) {
            try {
                val manifestUrl = URL(URL(page.url), href.replace("&amp;", "&"))
                val manifest = fetch(manifestUrl.toString(), userAgent)
                if (manifest != null && manifest.code in 200..299) {
                    val json = JSONObject(manifest.body)
                    val scope = URL(manifestUrl, json.optString("scope").ifBlank { "./" }).toString()
                    val start = json.optString("start_url").takeIf { it.isNotBlank() }
                        ?.let { URL(manifestUrl, it).toString() }
                        ?.takeIf { it.startsWith(scope) }
                        ?: page.url
                    return Result.Ok(start, scope)
                }
            } catch (_: Exception) {
                // Fall through: no usable manifest.
            }
        }
        // No manifest: stay inside the folder of the page that answered.
        return Result.Ok(page.url, URL(URL(page.url), "./").toString())
    }

    /** GET with redirects followed by hand, so http → https hops work too. */
    private fun fetch(start: String, userAgent: String): Page? {
        var url = start
        repeat(8) {
            val conn = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (_: Exception) {
                return null
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", "text/html,application/manifest+json,application/json;q=0.9,*/*;q=0.8")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return Page(url, code, "")
                    url = keepHttps(URL(url), URL(URL(url), location)).toString()
                    return@repeat
                }
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                return Page(url, code, stream?.use { readLimited(it, 512 * 1024) } ?: "")
            } catch (_: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * Some servers redirect "/rainbead" to "http://…/rainbead/" even when reached over HTTPS
     * (e.g. a directory-slash redirect behind a TLS proxy): never step down on the same host.
     */
    private fun keepHttps(from: URL, to: URL): URL =
        if (from.protocol == "https" && to.protocol == "http" && from.host.equals(to.host, ignoreCase = true)) {
            URL("https", to.host, if (to.port == 80) -1 else to.port, to.file)
        } else {
            to
        }

    private fun readLimited(input: InputStream, limit: Int): String {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, total, limit - total)
            if (n < 0) break
            total += n
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }
}
