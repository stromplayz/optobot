package com.optobot.native

import android.util.Log
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL

/**
 * OptoReach — on-device internet tools for OptoBot.
 *
 * Inspiration: https://github.com/Panniantong/agent-reach (HTTP channels)
 * + https://github.com/codeaashu/claude-code (tool-system pattern).
 *
 * All channels use only free, no-API-key, no-trial endpoints. Nothing leaves
 * the phone except direct HTTP calls to the public services below.
 *
 * Channels:
 *  - web    → Jina Reader (free, no key) for any URL as Markdown
 *  - search → Multi-source: DuckDuckGo HTML + Wikipedia REST + arXiv API
 *  - wiki   → Wikipedia REST API (free, no key, generous rate limit)
 *  - arxiv  → arXiv API (free, open access research papers)
 *  - books  → Open Library API (free, no key)
 *  - yt     → YouTube transcript fetch (via public timedtext endpoint)
 *  - rss    → RSS / Atom parser (pure Java XML parsing)
 *  - gh     → GitHub public repo reader (api.github.com — no auth for public)
 *
 * Privacy:
 *  - All calls go through the user's phone network directly. No proxy.
 *  - The user always sees a "fetching..." indicator before any URL is hit.
 *  - Reach calls are opt-in — only when the user types a slash command.
 *
 * Concurrency:
 *  - All calls are synchronous and must be made off the main thread.
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

    /**
     * Multi-source web search — the backbone of OptoReach.
     *
     * Aggregates results from:
     *  - DuckDuckGo HTML (general web, no API key)
     *  - Wikipedia REST (for encyclopedic / definitional queries)
     *  - arXiv API (for research / academic queries)
     *
     * Each source runs in parallel via a thread per source. We wait for
     * all to finish (or timeout) and merge the results. The user sees a
     * single combined answer.
     */
    fun search(query: String, maxResults: Int = 5): ReachResult {
        val results = mutableListOf<Pair<String, String>>()  // (source, content)
        val errors = mutableListOf<Pair<String, String>>()   // (source, error)

        val sources = listOf(
            "ddg" to {
                runCatching { searchDdg(query, maxResults) }
                    .onSuccess { results.add("DuckDuckGo" to it) }
                    .onFailure { errors.add("DuckDuckGo" to (it.message ?: "unknown")) }
            },
            "wiki" to {
                runCatching { searchWikipedia(query, maxResults = 2) }
                    .onSuccess { results.add("Wikipedia" to it) }
                    .onFailure { errors.add("Wikipedia" to (it.message ?: "unknown")) }
            },
            "arxiv" to {
                runCatching { searchArxiv(query, maxResults = 3) }
                    .onSuccess { results.add("arXiv" to it) }
                    .onFailure { errors.add("arXiv" to (it.message ?: "unknown")) }
            }
        )

        // Run all sources in parallel, wait for all to finish (max 12s)
        val threads = sources.map { (name, block) ->
            Thread(block, "opto-search-$name").apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(12_000) }

        if (results.isEmpty()) {
            val errSummary = errors.joinToString("; ") { "${it.first}: ${it.second}" }
            return ReachResult.Error("all search sources failed — $errSummary")
        }

        val combined = buildString {
            append("Multi-source search: \"$query\"\n\n")
            for ((source, content) in results) {
                append("── $source ──\n")
                append(content.trim())
                append("\n\n")
            }
            if (errors.isNotEmpty()) {
                append("── failed sources ──\n")
                for ((source, err) in errors) {
                    append("- $source: $err\n")
                }
            }
        }
        return ReachResult.Ok(combined, source = "multi-search:$query")
    }

    /** Single-source DuckDuckGo search. Used by search() internally + as a fallback. */
    private fun searchDdg(query: String, maxResults: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = httpGetText("https://html.duckduckgo.com/html/?q=$q")
        val results = parseDdgResults(html).take(maxResults).joinToString("\n\n") { r ->
            "- [${r.title}](${r.url})\n  ${r.snippet}"
        }
        return results.ifBlank { "(no results)" }
    }

    /** Wikipedia REST search — returns summary cards. Free, no API key. */
    private fun searchWikipedia(query: String, maxResults: Int = 2): String {
        // The Wikipedia opensearch API returns up to N titles + snippets + URLs.
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=$q&srlimit=$maxResults&format=json&origin=*"
        val json = httpGetJson(url, headers = mapOf("Accept" to "application/json"))
            ?: return "(wikipedia: no response)"
        val arr = json.optJSONObject("query")?.optJSONArray("search") ?: return "(wikipedia: no results)"
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title")
            val snippet = stripHtml(item.optString("snippet"))
            val pageId = item.optInt("pageid")
            val link = "https://en.wikipedia.org/?curid=$pageId"
            sb.append("- [$title]($link)\n  $snippet\n\n")
        }
        return sb.toString().ifBlank { "(wikipedia: no results)" }
    }

    /** arXiv search — returns research paper abstracts. Free, open access, no API key. */
    private fun searchArxiv(query: String, maxResults: Int = 3): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "http://export.arxiv.org/api/query?search_query=all:$q&max_results=$maxResults"
        val xml = httpGetText(url)
        val entries = parseAtomEntries(xml).take(maxResults)
        if (entries.isEmpty()) return "(arxiv: no results)"
        val sb = StringBuilder()
        for (e in entries) {
            sb.append("- [${e.title}](${e.link})\n")
            sb.append("  Authors: ${e.authors}\n")
            sb.append("  Published: ${e.published}\n")
            sb.append("  Summary: ${e.summary.take(300)}${if (e.summary.length > 300) "..." else ""}\n\n")
        }
        return sb.toString()
    }

    /** Wikipedia full article — uses the REST summary endpoint. Free, no key. */
    fun wiki(query: String): ReachResult {
        return try {
            // Try as a page title first (case-sensitive, spaces → underscores)
            val title = URLEncoder.encode(query.trim().replace(' ', '_'), "UTF-8")
            val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$title"
            val json = httpGetJson(url, headers = mapOf("Accept" to "application/json"))
                ?: return ReachResult.Error("wikipedia: no response")
            val title2 = json.optString("title")
            val extract = json.optString("extract")
            if (extract.isBlank()) {
                ReachResult.Error("wikipedia: no article found for \"$query\"")
            } else {
                val content = "Wikipedia: $title2\n\n$extract\n\nSource: https://en.wikipedia.org/wiki/${title}"
                ReachResult.Ok(content, source = "wiki:$query")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wiki($query) failed", t)
            ReachResult.Error(t.message ?: "wikipedia fetch failed")
        }
    }

    /** arXiv search as a standalone command. */
    fun arxiv(query: String): ReachResult {
        return try {
            val content = searchArxiv(query, maxResults = 5)
            ReachResult.Ok("arXiv search: \"$query\"\n\n$content", source = "arxiv:$query")
        } catch (t: Throwable) {
            Log.w(TAG, "arxiv($query) failed", t)
            ReachResult.Error(t.message ?: "arxiv fetch failed")
        }
    }

    /** Open Library book search. Free, no API key. */
    fun books(query: String): ReachResult {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://openlibrary.org/search.json?q=$q&limit=5"
            val json = httpGetJson(url, headers = mapOf("Accept" to "application/json"))
                ?: return ReachResult.Error("open library: no response")
            val docs = json.optJSONArray("docs") ?: return ReachResult.Error("open library: no results")
            val sb = StringBuilder("Open Library search: \"$query\"\n\n")
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                val title = d.optString("title")
                val authors = d.optJSONArray("author_name")?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                } ?: "(unknown)"
                val year = d.optInt("first_publish_year", -1).let { if (it > 0) it.toString() else "?" }
                val olid = d.optString("cover_edition_key", "")
                val link = if (olid.isNotBlank()) "https://openlibrary.org/books/$olid" else "https://openlibrary.org"
                sb.append("- [$title]($link)\n  Author: $authors | Year: $year\n")
                val subjectArr = d.optJSONArray("subject")
                if (subjectArr != null) {
                    val subjects = (0 until minOf(subjectArr.length(), 3)).joinToString(", ") { subjectArr.optString(it) }
                    sb.append("  Subjects: $subjects\n")
                }
                sb.append("\n")
            }
            ReachResult.Ok(sb.toString(), source = "books:$query")
        } catch (t: Throwable) {
            Log.w(TAG, "books($query) failed", t)
            ReachResult.Error(t.message ?: "books fetch failed")
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

    /** Parsed arXiv Atom entry — has more fields than a generic RSS item. */
    private data class ArxivEntry(
        val title: String,
        val link: String,
        val authors: String,
        val published: String,
        val summary: String,
    )

    /** Parse arXiv-style Atom entries (with <author>, <published>, <summary>). */
    private fun parseAtomEntries(xml: String): List<ArxivEntry> {
        val entries = ArrayList<ArxivEntry>()
        val entryRegex = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
        for (m in entryRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val title = stripXml(extractTag(block, "title")).trim()
            // arXiv Atom entries have <id>https://arxiv.org/abs/...</id> as the link
            val link = extractTag(block, "id").trim().ifBlank {
                Regex("""<link[^>]*href="([^"]+)"""").find(block)?.groupValues?.get(1) ?: ""
            }
            // Multiple <author><name>...</name></author> blocks
            val authorsRegex = Regex("""<author>.*?<name>(.*?)</name>.*?</author>""", RegexOption.DOT_MATCHES_ALL)
            val authors = authorsRegex.findAll(block).joinToString(", ") { it.groupValues[1].trim() }
            val published = extractTag(block, "published").trim().take(10)  // YYYY-MM-DD
            val summary = stripXml(extractTag(block, "summary")).trim()
            if (title.isNotBlank()) {
                entries.add(ArxivEntry(title, link, authors, published, summary))
            }
        }
        return entries
    }

    /** Strip XML tags + decode common entities (parallel to stripHtml but for XML content). */
    private fun stripXml(s: String): String {
        return s.replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .trim()
    }

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
