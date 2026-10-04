package com.optobot.native

import android.util.Log
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL

/**
 * OptoReach — on-device port of the Agent-Reach channels.
 *
 * Inspiration: https://github.com/Panniantong/agent-reach
 *
 * What we port:
 *  - Web (any URL via Jina Reader — free, no API key)
 *  - YouTube transcript fetch (via youtube_transcript_api-style endpoint)
 *  - RSS / Atom parser (pure Java XML parsing)
 *  - GitHub public repo reader (via api.github.com — no auth needed for public)
 *  - Web search (via DuckDuckGo HTML — no API key)
 *
 * What we deliberately skip:
 *  - Twitter, Reddit, Instagram, XiaoHongShu, Facebook, LinkedIn — these need
 *    cookies / authenticated browser sessions and are not appropriate for an
 *    Android app. Users who want them can run agent-reach on their desktop and
 *    pipe results into OptoBot via the /paste command.
 *  - Bilibili, Boss — same reason.
 *
 * Privacy:
 *  - All reach calls go through the user's phone network directly.
 *  - We do NOT proxy through any OptoBot server.
 *  - The model decides when to call these tools (via slash commands or
 *    tool-call markers in its output). The user always sees a "Fetching X"
 *    indicator before any URL is hit.
 *
 * Concurrency:
 *  - All calls are synchronous and must be made off the main thread.
 *    OptoReachTool callers (the engine + JS bridge) are responsible for
 *    dispatching to a background thread.
 */
object OptoReach {

    private const val TAG = "OptoReach"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 OptoBot/0.1"
    private const val MAX_RESPONSE_BYTES = 5L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Fetch a web page as Markdown, via Jina Reader (free, no API key). */
    fun web(url: String): ReachResult {
        return try {
            val normalized = normalizeUrl(url)
            val jinaUrl = "https://r.jina.ai/$normalized"
            val body = httpGetText(jinaUrl, headers = mapOf("Accept" to "text/plain"))
            // Detect anti-bot challenge pages and surface a clear error
            val sample = body.take(4096).lowercase()
            if ("title: just a moment..." in sample || "cloudflare" in sample && "ray id" in sample) {
                ReachResult.Error("anti-bot challenge — try a different URL or channel")
            } else {
                ReachResult.Ok(body, source = "jina:$normalized")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "web($url) failed", t)
            ReachResult.Error(t.message ?: "web fetch failed")
        }
    }

    /** Web search via DuckDuckGo HTML endpoint (no API key needed). */
    fun search(query: String, maxResults: Int = 5): ReachResult {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val html = httpGetText("https://html.duckduckgo.com/html/?q=$q")
            val results = parseDdgResults(html).take(maxResults).joinToString("\n\n") { r ->
                "- [${r.title}](${r.url})\n  ${r.snippet}"
            }
            ReachResult.Ok("Search: \"$query\"\n\n$results", source = "ddg:$query")
        } catch (t: Throwable) {
            Log.w(TAG, "search($query) failed", t)
            ReachResult.Error(t.message ?: "search failed")
        }
    }

    /** Fetch YouTube transcript (subtitles) for a video ID or URL. */
    fun youtube(input: String): ReachResult {
        return try {
            val videoId = extractYoutubeId(input)
                ?: return ReachResult.Error("could not extract video id from: $input")
            // Use the public timedtext API. We try English first, fall back to any language.
            val url = "https://video.google.com/timedtext?lang=en&v=$videoId"
            val xml = httpGetText(url)
            val transcript = parseTimedTextXml(xml)
            if (transcript.isBlank()) {
                ReachResult.Error("no English transcript available for $videoId (try another video)")
            } else {
                ReachResult.Ok(
                    "YouTube transcript: $videoId\n\n$transcript",
                    source = "yt:$videoId"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "youtube($input) failed", t)
            ReachResult.Error(t.message ?: "youtube fetch failed")
        }
    }

    /** Fetch and parse an RSS / Atom feed. */
    fun rss(url: String): ReachResult {
        return try {
            val xml = httpGetText(normalizeUrl(url))
            val items = parseRssOrAtom(xml)
            val summary = items.take(10).joinToString("\n\n") { item ->
                "- ${item.title}\n  ${item.link}\n  ${item.snippet}"
            }
            ReachResult.Ok("RSS feed: $url\n\n$summary", source = "rss:$url")
        } catch (t: Throwable) {
            Log.w(TAG, "rss($url) failed", t)
            ReachResult.Error(t.message ?: "rss fetch failed")
        }
    }

    /** Fetch a public GitHub repo's README + metadata (no auth needed). */
    fun github(repoInput: String): ReachResult {
        return try {
            val (owner, repo) = parseGithubRepo(repoInput)
                ?: return ReachResult.Error("expected 'owner/repo' or full github URL")
            // README
            val readmeUrl = "https://api.github.com/repos/$owner/$repo/readme"
            val readmeB64 = httpGetJson(readmeUrl, headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to USER_AGENT
            ))?.optString("content") ?: ""
            val readme = if (readmeB64.isNotEmpty()) {
                String(android.util.Base64.decode(readmeB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else "(no README)"
            // Repo metadata
            val metaUrl = "https://api.github.com/repos/$owner/$repo"
            val meta = httpGetJson(metaUrl, headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to USER_AGENT
            ))
            val description = meta?.optString("description") ?: "(no description)"
            val stars = meta?.optInt("stargazers_count", 0) ?: 0
            val language = meta?.optString("language") ?: "(unknown)"
            ReachResult.Ok(
                "GitHub repo: $owner/$repo\n" +
                "Stars: $stars | Language: $language\n" +
                "Description: $description\n\n--- README ---\n\n$readme",
                source = "gh:$owner/$repo"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "github($repoInput) failed", t)
            ReachResult.Error(t.message ?: "github fetch failed")
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Internal helpers
    // ───────────────────────────────────────────────────────────────────

    private fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.inputStream.use { input ->
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    baos.write(buf, 0, n)
                    read += n
                    if (read > MAX_RESPONSE_BYTES) {
                        baos.write("\n[...truncated at 5MB by OptoReach...]".toByteArray())
                        break
                    }
                }
                return baos.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetJson(url: String, headers: Map<String, String> = emptyMap()): org.json.JSONObject? {
        val text = httpGetText(url, headers)
        return if (text.isNotBlank()) org.json.JSONObject(text) else null
    }

    private fun normalizeUrl(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        return u
    }

    private fun extractYoutubeId(input: String): String? {
        // youtu.be/ID, youtube.com/watch?v=ID, youtube.com/embed/ID
        val patterns = listOf(
            Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{6,})"""),
            Regex("""youtube\.com/embed/([A-Za-z0-9_-]{6,})""")
        )
        for (p in patterns) {
            val m = p.find(input)
            if (m != null) return m.groupValues[1]
        }
        // Maybe input IS the video id
        if (input.matches(Regex("""^[A-Za-z0-9_-]{6,}$"""))) return input
        return null
    }

    private data class DdgResult(val title: String, val url: String, val snippet: String)

    private fun parseDdgResults(html: String): List<DdgResult> {
        // DuckDuckGo HTML results look like:
        // <a class="result__a" href="https://...">Title</a>
        // <a class="result__snippet" href="...">Snippet text</a>
        val results = ArrayList<DdgResult>()
        val titleRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.+?)</a>""")
        val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.+?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val titles = titleRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()
        for ((i, t) in titles.withIndex()) {
            val title = stripHtml(t.groupValues[2]).trim()
            val rawUrl = t.groupValues[1]
            // DDG uses redirect URLs like //duckduckgo.com/l/?uddg=<encoded>
            val realUrl = if (rawUrl.contains("uddg=")) {
                val q = rawUrl.substringAfter("uddg=").substringBefore("&")
                java.net.URLDecoder.decode(q, "UTF-8")
            } else rawUrl
            val snippet = if (i < snippets.size) stripHtml(snippets[i].groupValues[1]).trim() else ""
            results.add(DdgResult(title, realUrl, snippet))
        }
        return results
    }

    private data class RssItem(val title: String, val link: String, val snippet: String)

    private fun parseRssOrAtom(xml: String): List<RssItem> {
        // Best-effort regex parsing — keeps us XML-parser-impl-agnostic.
        // RSS: <item><title>X</title><link>Y</link><description>Z</description></item>
        // Atom: <entry><title>X</title><link href="Y"/><summary>Z</summary></entry>
        val items = ArrayList<RssItem>()
        val itemRegex = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val entryRegex = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
        for (m in itemRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val title = extractTag(block, "title")
            val link = extractTag(block, "link")
            val snippet = stripHtml(extractTag(block, "description")).take(200)
            items.add(RssItem(title, link, snippet))
        }
        for (m in entryRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val title = extractTag(block, "title")
            val link = Regex("""<link[^>]*href="([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            val snippet = stripHtml(extractTag(block, "summary") + extractTag(block, "content")).take(200)
            items.add(RssItem(title, link, snippet))
        }
        return items
    }

    private fun extractTag(xml: String, tag: String): String {
        val regex = Regex("""<$tag[^>]*>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1) ?: ""
    }

    private fun parseTimedTextXml(xml: String): String {
        // <transcript><text start="0.5" dur="2.3">Hello world</text>...</transcript>
        val regex = Regex("""<text[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        for (m in regex.findAll(xml)) {
            val text = m.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
            sb.append(text).append(' ')
        }
        return sb.toString().trim()
    }

    private fun stripHtml(s: String): String {
        return s.replace(Regex("""<[^>]+>"""), "").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
            .replace("&quot;", "\"").replace("&nbsp;", " ").trim()
    }

    private fun parseGithubRepo(input: String): Pair<String, String>? {
        val s = input.trim()
        // Try "owner/repo"
        val m1 = Regex("""^([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)$""").find(s)
        if (m1 != null) return m1.groupValues[1] to m1.groupValues[2].removeSuffix(".git")
        // Try "https://github.com/owner/repo"
        val m2 = Regex("""github\.com/([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)""").find(s)
        if (m2 != null) return m2.groupValues[1] to m2.groupValues[2].removeSuffix(".git")
        return null
    }

    sealed class ReachResult {
        data class Ok(val content: String, val source: String) : ReachResult()
        data class Error(val message: String) : ReachResult()
    }
}
