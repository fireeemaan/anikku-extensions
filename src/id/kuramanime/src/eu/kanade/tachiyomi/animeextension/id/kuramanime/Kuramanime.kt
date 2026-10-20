package eu.kanade.tachiyomi.animeextension.id.kuramanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kuramanime :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name = "Kuramanime"

    override val baseUrl = "https://v20.kuramanime.ing"

    override val lang = "id"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?page=$page")

    override fun popularAnimeSelector() = ANIME_SELECTOR

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("h5 a[href]")
            ?: element.selectFirst("a[href]")
            ?: element.takeIf { it.tagName() == "a" }
            ?: throw IllegalStateException("No link for anime item")

        setUrlWithoutDomain(link.absUrl("href").ifBlank { link.attr("href") })
        thumbnail_url = element.selectFirst("div.set-bg")?.attr("data-setbg")
            ?: element.selectFirst("[data-setbg]")?.attr("data-setbg")
        title = element.selectFirst("h5")?.text()?.trim().orEmpty().ifBlank { link.text().trim() }
    }

    override fun popularAnimeNextPageSelector() = null

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response.useAsJsoup())

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?order_by=updated&page=$page")

    override fun latestUpdatesSelector() = ANIME_SELECTOR

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response.useAsJsoup())

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/anime?search=$query&page=$page")

    override fun searchAnimeSelector() = ANIME_SELECTOR

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = null

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response.useAsJsoup())

    /**
     * The pager only renders a window of page numbers around the current page and never a "next"
     * arrow, so "has next" is derived from the highest visible page number. The mobile layout has no
     * pager at all, so a non-empty page is treated as "more may follow".
     */
    private fun parseAnimeList(document: Document): AnimesPage {
        val animes = document.select(ANIME_SELECTOR).map(::popularAnimeFromElement)

        val pagination = document.selectFirst("div.product__pagination")
        val currentPage = pagination?.selectFirst("a.current-page")?.text()?.trim()?.toIntOrNull()
        val hasNextPage = if (pagination == null || currentPage == null) {
            animes.isNotEmpty()
        } else {
            pagination.select("a.page__link").any { link ->
                link.text().trim().toIntOrNull()?.let { it > currentPage } == true
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime__details__pic")?.attr("data-setbg")
            ?: document.selectFirst("div[data-setbg]")?.attr("data-setbg")

        val details = document.selectFirst("div.anime__details__text")!!

        title = details.selectFirst("div.anime__details__title > h3")?.text()
            ?.replace("Judul: ", "")
            ?.trim()
            ?: ""

        val infos = details.selectFirst("div.anime__details__widget")!!
        artist = infos.select("li:contains(Studio:) a").eachText()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString()
            .takeUnless(String::isEmpty)
        status = parseStatus(infos.selectFirst("li:contains(Status:) a")?.text())

        genre = infos.select("li:contains(Genre:) a, li:contains(Tema:) a, li:contains(Demografis:) a")
            .eachText()
            .map { it.trim().trimEnd(',', ' ') }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString()
            .takeUnless(String::isEmpty)

        description = buildString {
            details.selectFirst("p#synopsisField")?.text()?.also(::append)

            details.selectFirst("div.anime__details__title > span")?.text()
                ?.also { append("\n\nAlternative names: $it\n") }

            infos.select("ul > li").eachText().forEach { append("\n$it") }
        }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()) {
        "Sedang Tayang" -> SAnime.ONGOING
        "Selesai Tayang" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    /**
     * The episode popover is capped (12 links on short series, the newest 15 on long ones), so the
     * full range is rebuilt from the `Episode:` field of the info widget whenever it is numeric.
     */
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()

        val html = document.selectFirst(episodeListSelector())?.attr("data-content")
            ?: return emptyList()

        val links = Jsoup.parse(html).select("a[href*=/episode/]")
        if (links.isEmpty()) return emptyList()

        val episodes = links.map(::episodeFromElement).distinctBy { it.url }

        val total = document.selectFirst("li:contains(Episode:) a")?.text()?.trim()?.toIntOrNull()
            ?: return episodes.sortedBy { it.episode_number }

        if (total <= episodes.size) return episodes.sortedBy { it.episode_number }

        val sample = links[0].absUrl("href").ifBlank { links[0].attr("href") }
        val prefix = sample.substringBeforeLast("/episode/", sample)

        return (1..total).map { episodeNumber ->
            SEpisode.create().apply {
                name = "Ep $episodeNumber"
                episode_number = episodeNumber.toFloat()
                setUrlWithoutDomain("$prefix/episode/$episodeNumber")
            }
        }
    }

    override fun episodeListSelector() = "a#episodeLists"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.absUrl("href").ifBlank { element.attr("href") })
        name = element.text().trim()
        episode_number = name.filter(Char::isDigit).toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }

    /**
     * The whole player flow lives here instead of in `videoListParse` because everything past the
     * first request is asynchronous: the base shim declares `videoListParse` as a plain function, so
     * driving it required `runBlocking` + `execute()`, and any failure in there (a blocked dispatcher
     * thread, a slow CDN) was swallowed by `runCatching` and surfaced as an empty list — which the app
     * renders as "No available videos". `getVideoList` is `open suspend`, so the same logic can use
     * `awaitSuccess()` and call the extractors' `suspend` functions directly.
     *
     * The player markup is gated behind a per-session token handed out in two steps, and the endpoint
     * that finally returns it **only accepts `POST`** carrying an `authorization` form field *and* the
     * session cookie set by the episode page.
     *
     * 1. `meta[name=csrf-token]`, `input#checkEp` (its value is the page-index endpoint) and
     *    `[data-kk]` (id of the generated JS holding `window.process.env`) come from the episode page.
     * 2. `GET #checkEp` -> the page index as a bare number.
     * 3. `GET /assets/js/<data-kk>.js` -> the `MIX_*` names used by the two query parameters below.
     * 4. `GET /<MIX_PREFIX_AUTH_ROUTE_PARAM><MIX_AUTH_ROUTE_PARAM>` with `X-Fuck-ID` -> the token.
     * 5. `POST <episodeUrl>?<token>=…&<server>=…&page=…` with `authorization` in the body.
     *
     * Each step throws with a short reason when it cannot proceed, so a failure shows up in the app as
     * a readable message instead of a silent empty list.
     */
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = joinUrl(episode.url)
        val cookies = mutableMapOf<String, String>()

        val pageResponse = client.newCall(GET(episodeUrl, headers)).awaitSuccess()
        cookies.harvest(pageResponse)
        val doc = pageResponse.useAsJsoup()

        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: fail("episode page has no csrf-token (url=$episodeUrl)")
        val checkUrl = doc.selectFirst("input#checkEp")?.attr("value")
            ?: fail("episode page has no input#checkEp (url=$episodeUrl)")
        val jsRouteParam = doc.selectFirst("[$JS_ROUTE_ATTR]")?.attr(JS_ROUTE_ATTR)
            ?: fail("episode page has no [$JS_ROUTE_ATTR]")

        val page = runCatching {
            client.newCall(
                GET(
                    checkUrl,
                    headersBuilder()
                        .set("X-Requested-With", "XMLHttpRequest")
                        .set("Cookie", cookies.toCookieHeader())
                        .build(),
                ),
            )
                .awaitSuccess()
                .use { it.cookiesInto(cookies) }
                .filter(Char::isDigit)
                .ifBlank { "1" }
        }.getOrDefault("1")

        val env = runCatching {
            client.newCall(
                GET(
                    "$baseUrl/assets/js/$jsRouteParam.js",
                    headersBuilder().set("Cookie", cookies.toCookieHeader()).build(),
                ),
            )
                .awaitSuccess()
                .bodyString()
                .let(::parseEnv)
        }.getOrNull().orEmpty()

        if (env.isEmpty()) fail("env JS is empty (data-kk=$jsRouteParam)")

        val tokenParam = env["MIX_PAGE_TOKEN_KEY"] ?: fail("env has no MIX_PAGE_TOKEN_KEY")
        val serverParam = env["MIX_STREAM_SERVER_KEY"] ?: fail("env has no MIX_STREAM_SERVER_KEY")
        val authPath = (env["MIX_PREFIX_AUTH_ROUTE_PARAM"] ?: "") + (env["MIX_AUTH_ROUTE_PARAM"] ?: "")
        if (authPath.isBlank()) fail("env has no auth route params")

        val token = runCatching {
            val tokenHeaders = headersBuilder()
                .set("X-Fuck-ID", "${env["MIX_AUTH_KEY"].orEmpty()}:${env["MIX_AUTH_TOKEN"].orEmpty()}")
                .set("X-Request-ID", getRandomString(6))
                .set("X-Request-Index", "0")
                .set("Cookie", cookies.toCookieHeader())
                .build()

            client.newCall(GET("$baseUrl/$authPath", tokenHeaders))
                .awaitSuccess()
                .use { it.cookiesInto(cookies) }
                .trim('"', ' ', '\n', '\r')
        }.getOrNull().orEmpty()

        if (token.isBlank()) fail("token request returned blank ($authPath)")

        val servers = doc.select("select#changeServer > option")
            .map { it.attr("value") }
            .filter(String::isNotBlank)
        if (servers.isEmpty()) fail("episode page has no select#changeServer options")

        val playerHeaders = headersBuilder()
            .set("Referer", episodeUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-CSRF-TOKEN", csrfToken)
            .set("Accept", "text/html, */*; q=0.01")
            .set("Cookie", cookies.toCookieHeader())
            .build()

        // The server validates the value, not just its presence: a random string is rejected, so this
        // is a shared constant rather than a per-request nonce. Keep it in one place.
        val authBody = FormBody.Builder()
            .add("authorization", AUTH_SIGNATURE)
            .build()

        var lastRejected = 0
        for (server in servers) {
            val playerUrl = episodeUrl.toHttpUrl().newBuilder()
                .addQueryParameter(tokenParam, token)
                .addQueryParameter(serverParam, server)
                .addQueryParameter("page", page)
                .build()
                .toString()

            val videos = runCatching {
                val body = client.newCall(POST(playerUrl, body = authBody, headers = playerHeaders))
                    .awaitSuccess()
                    .bodyString()

                if (body.contains(PLAYER_ERROR_MARKER)) {
                    lastRejected = body.length
                    emptyList()
                } else {
                    parseVideos(Jsoup.parse(body, playerUrl), server, playerHeaders)
                }
            }.getOrDefault(emptyList())

            // Stop at the first server that actually yields a playable source; the endpoint is
            // single-shot per episode and repeats the same rejection for every server.
            if (videos.isNotEmpty()) return videos
        }

        fail("all ${servers.size} servers rejected (token=$token page=$page lastBody=$lastRejected)")
    }

    private suspend fun parseVideos(document: Document, server: String, videoHeaders: Headers): List<Video> {
        val videos = mutableListOf<Video>()

        val player = document.selectFirst("video#player")

        // `data-hls-src` is empty for the kuramadrive server, so it must not be emitted blindly.
        val hls = player?.attr("data-hls-src")?.takeIf(String::isNotBlank)
        val direct = player?.attr("src")?.takeIf(String::isNotBlank)

        if (hls != null) videos += Video(hls, "$server - HLS", hls, videoHeaders)
        if (direct != null) videos += Video(direct, "$server - ${qualityOf(direct)}", direct, videoHeaders)

        // Each quality is a separate <source size="720">; Jsoup decodes the `&amp;` in `src` for us.
        document.select("video#player source[src], div.video-content source[src]").forEach { source ->
            val url = source.attr("src").takeIf(String::isNotBlank) ?: return@forEach
            val quality = source.attr("size").trim().takeIf(String::isNotBlank)?.let { "${it}p" }
                ?: qualityOf(url)
            videos += Video(url, "$server - $quality", url, videoHeaders)
        }

        if (videos.isNotEmpty()) return videos.distinctBy { it.videoUrl }

        val iframe = document.selectFirst("div.video-content iframe[src]")?.attr("src")
            ?: document.selectFirst("#animeVideoPlayer iframe[src]")?.attr("src")
            ?: return emptyList()

        return when {
            "filemoon" in iframe -> filemoonExtractor.videosFromUrl(iframe)
            "filelions" in iframe || "streamwish" in iframe -> streamWishExtractor.videosFromUrl(iframe)
            "streamtape" in iframe -> streamtapeExtractor.videosFromUrl(iframe)
            "vidguard" in iframe -> vidguardExtractor.videosFromUrl(iframe)
            else -> listOf(Video(iframe, server, iframe, videoHeaders))
        }
    }

    /** `episode.url` is normally a path, but guard against a stored absolute URL or a missing slash. */
    private fun joinUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/$path"
    }

    private fun fail(reason: String): Nothing = throw IllegalStateException("Kuramanime: $reason")

    private fun qualityOf(url: String): String = QUALITY_REGEX.find(url)?.groupValues?.get(1)?.let { "${it}p" } ?: "Direct"

    private fun parseEnv(script: String): Map<String, String> {
        val envBlock = ENV_BLOCK_REGEX.find(script)?.groupValues?.get(1) ?: return emptyMap()
        return ENV_VAR_REGEX.findAll(envBlock).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Reads the body and folds any `Set-Cookie` the response carried into [cookies]. */
    private fun Response.cookiesInto(cookies: MutableMap<String, String>): String {
        cookies.harvest(this)
        return bodyString()
    }

    private fun MutableMap<String, String>.harvest(response: Response) {
        response.headers("Set-Cookie").forEach { raw ->
            val name = raw.substringBefore('=').trim()
            val value = raw.substringAfter('=', "").substringBefore(';').trim()
            if (name.isNotEmpty() && value.isNotEmpty()) put(name, value)
        }
    }

    private fun Map<String, String>.toCookieHeader(): String = entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun getRandomString(length: Int = 8): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val ANIME_SELECTOR = "div#animeList div.filter__gallery > a, div#animeList div.product__item"
        private const val JS_ROUTE_ATTR = "data-kk"
        private const val PLAYER_ERROR_MARKER = "Terjadi kesalahan saat mengambil video"

        /** Validated server-side, stable across sessions and episodes — not a per-request nonce. */
        private const val AUTH_SIGNATURE = "kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur"

        private val ENV_BLOCK_REGEX by lazy { Regex("""env:\s*\{([\s\S]*?)}""") }
        private val ENV_VAR_REGEX by lazy { Regex("""(\w+)\s*:\s*['"]([^'"]+)['"]""") }
        private val QUALITY_REGEX by lazy { Regex("""(?:^|[-_. ])(\d{3,4})p""", RegexOption.IGNORE_CASE) }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
