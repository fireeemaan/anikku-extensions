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
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
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
    override fun videoListSelector() = "video#player"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }

    /**
     * The player is no longer served as a plain `<source>` list. The episode page now hands out a
     * per-session token through a dynamically generated JS file (`[data-kk]` -> `/assets/js/<id>.js`
     * which defines `window.process.env`), and the player markup only comes back when the request
     * carries `?<MIX_PAGE_TOKEN_KEY>=<token>&<MIX_STREAM_SERVER_KEY>=<server>&page=<n>`.
     */
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val episodeUrl = response.request.url

        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return emptyList()
        val checkUrl = doc.selectFirst("input#checkEp")?.attr("value") ?: return emptyList()
        val jsRouteParam = doc.selectFirst("[$JS_ROUTE_ATTR]")?.attr(JS_ROUTE_ATTR) ?: return emptyList()

        val page = runCatching {
            client.newCall(GET(checkUrl, headers.newBuilder().set("X-Requested-With", "XMLHttpRequest").build()))
                .execute()
                .bodyString()
                .filter(Char::isDigit)
                .ifBlank { "1" }
        }.getOrDefault("1")

        val env = runCatching {
            client.newCall(GET("$baseUrl/assets/js/$jsRouteParam.js", headers))
                .execute()
                .bodyString()
                .let(::parseEnv)
        }.getOrNull().orEmpty()

        val tokenParam = env["MIX_PAGE_TOKEN_KEY"] ?: return emptyList()
        val serverParam = env["MIX_STREAM_SERVER_KEY"] ?: return emptyList()
        val authPath = (env["MIX_PREFIX_AUTH_ROUTE_PARAM"] ?: "") + (env["MIX_AUTH_ROUTE_PARAM"] ?: "")
        if (authPath.isBlank()) return emptyList()

        val token = runCatching {
            val tokenHeaders = headersBuilder()
                .set("X-Fuck-ID", "${env["MIX_AUTH_KEY"].orEmpty()}:${env["MIX_AUTH_TOKEN"].orEmpty()}")
                .set("X-Request-ID", getRandomString(6))
                .set("X-Request-Index", "0")
                .build()

            client.newCall(GET("$baseUrl/$authPath", tokenHeaders))
                .execute()
                .bodyString()
                .trim('"', ' ', '\n', '\r')
        }.getOrNull().orEmpty()
        if (token.isBlank()) return emptyList()

        val servers = doc.select("select#changeServer > option")
            .map { it.attr("value") }
            .filter(String::isNotBlank)
        if (servers.isEmpty()) return emptyList()

        val playerHeaders = headersBuilder()
            .set("Referer", episodeUrl.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-CSRF-TOKEN", csrfToken)
            .build()

        // The player endpoint is single-shot per episode, so stop at the first server that actually
        // returns a playable source instead of burning every server in one go.
        for (server in servers) {
            val playerUrl = episodeUrl.newBuilder()
                .addQueryParameter(tokenParam, token)
                .addQueryParameter(serverParam, server)
                .addQueryParameter("page", page)
                .build()
                .toString()

            val result = runCatching {
                runBlocking {
                    val body = client.newCall(GET(playerUrl, playerHeaders)).execute().bodyString()
                    val videos = parseVideos(Jsoup.parse(body, playerUrl), server, playerHeaders)
                    videos to body.contains(PLAYER_ERROR_MARKER)
                }
            }.getOrNull() ?: continue

            if (result.first.isNotEmpty()) return result.first
            // The endpoint answers with an explicit "failed to fetch the video" panel for requests it
            // rejects; trying the remaining servers would only repeat the same rejection.
            if (result.second) return emptyList()
        }

        return emptyList()
    }

    private suspend fun parseVideos(document: Document, server: String, videoHeaders: Headers): List<Video> {
        val player = document.selectFirst("video#player")

        val hls = player?.attr("data-hls-src")?.takeIf(String::isNotBlank)
        val source = player?.attr("src")?.takeIf(String::isNotBlank)

        val videos = mutableListOf<Video>()
        if (hls != null) videos += Video(hls, "$server - HLS", hls, videoHeaders)
        if (source != null) videos += Video(source, "$server - ${qualityOf(source)}", source, videoHeaders)
        if (videos.isNotEmpty()) return videos

        document.select("video#player source[src], div.video-content source[src]")
            .map { it.attr("src") }
            .filter(String::isNotBlank)
            .forEach { videos += Video(it, "$server - ${qualityOf(it)}", it, videoHeaders) }
        if (videos.isNotEmpty()) return videos

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

    private fun qualityOf(url: String): String = QUALITY_REGEX.find(url)?.groupValues?.get(1)?.let { "${it}p" } ?: "Direct"

    private fun parseEnv(script: String): Map<String, String> {
        val envBlock = ENV_BLOCK_REGEX.find(script)?.groupValues?.get(1) ?: return emptyMap()
        return ENV_VAR_REGEX.findAll(envBlock).associate { it.groupValues[1] to it.groupValues[2] }
    }

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
