package eu.kanade.tachiyomi.animeextension.id.samehadaku

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.megaextractor.MegaExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Samehadaku :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val name: String = "Samehadaku"

    override val baseUrl: String = "https://v2.samehadaku.how"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    private val megaExtractor by lazy { MegaExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(listUrl(page, "popular"))

    override fun popularAnimeParse(response: Response): AnimesPage = getAnimeParse(response.asJsoup(), LIST_SELECTOR)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(listUrl(page, "update"))

    override fun latestUpdatesParse(response: Response): AnimesPage = getAnimeParse(response.asJsoup(), LIST_SELECTOR)

    /**
     * `/daftar-anime-2/page/1/` is 301-redirected to `/daftar-anime-2/`, so the first page is
     * requested without the paging segment.
     */
    private fun listUrl(page: Int, order: String): String = if (page <= 1) {
        "$baseUrl/daftar-anime-2/?order=$order"
    } else {
        "$baseUrl/daftar-anime-2/page/$page/?order=$order"
    }

    // ============================== Related ===============================

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        return document.select("div.recom-section article").mapNotNull { element ->
            SAnime.create().apply {
                val link = element.selectFirst("div.animposx > a[href]") ?: return@mapNotNull null
                setUrlWithoutDomain(link.absUrl("href"))
                title = element.selectFirst("div.title > h2")?.text()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                thumbnail_url = element.selectFirst("div.content-thumb > img")?.attr("src")
            }
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SamehadakuFilters.getSearchParameters(filters)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("daftar-anime-2")
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
            addPathSegment("")
            addQueryParameter("title", query)
        }.build()
        return GET("$url${params.filter}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = getAnimeParse(response.asJsoup(), LIST_SELECTOR)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = SamehadakuFilters.FILTER_LIST

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()

        // The detail page is a Tailwind layout: a "Nonton Anime <title>" card holds the poster,
        // synopsis and genre chips, while a "Detail Anime <title>" card holds the info table.
        val mainCard = doc.selectFirst("div.card:has(h2:contains(Nonton Anime))")
        val infoCard = doc.selectFirst("div.card:has(h3:contains(Detail Anime))")

        val infoRows = infoCard?.select("div > span")
            ?.chunked(2)
            ?.filter { it.size == 2 }
            ?.associate { it[0].text().trim().removeSuffix(":") to it[1].text().trim() }
            .orEmpty()

        val title = (mainCard ?: doc).selectFirst("h2:contains(Nonton Anime)")?.text()
            ?.substringAfter("Nonton Anime")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("h1[itemprop=headline]")?.text()?.removeSuffix(" Sub Indo")?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.removeSuffix(" Sub Indo")?.trim()

        return SAnime.create().apply {
            title?.let { this.title = it }
            author = infoRows["Studio"].orEmpty()
            status = parseStatus(infoRows["Status"])

            thumbnail_url = mainCard?.selectFirst("img[alt]")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

            description = mainCard?.selectFirst("div.entry-content p")?.text()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

            val genres = mainCard?.select("a[href*=/genre/]")
                ?.eachText()
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.distinct()
                .orEmpty()
            if (genres.isNotEmpty()) genre = genres.joinToString()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select("div.custom-scrollbar > div").mapNotNull { row ->
            val link = row.selectFirst("h4 a[href]") ?: return@mapNotNull null
            SEpisode.create().apply {
                setUrlWithoutDomain(link.absUrl("href").ifBlank { link.attr("href") })
                name = link.text().trim()
                episode_number = row.selectFirst("div.shrink-0 a")?.text()?.trim()?.toFloatOrNull()
                    ?: name.filter(Char::isDigit).toFloatOrNull()
                    ?: 1F
                date_upload = row.selectFirst("span")?.text()?.trim()?.let(DATE_FORMATTER::tryParse) ?: 0L
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val parseUrl = response.request.url.toUrl()
        val url = "${parseUrl.protocol}://${parseUrl.host}"
        return doc.select("#server > ul > li > div")
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(url, it) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first, it.second)
            }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.videoTitle.contains(quality) })
    }

    private fun getAnimeParse(document: Document, query: String): AnimesPage {
        val animes = document.select(query).mapNotNull { elm ->
            SAnime.create().apply {
                val link = elm.selectFirst("div.animposx > a[href]") ?: elm.selectFirst("a[href]")
                link?.absUrl("href")?.takeIf(String::isNotBlank)?.let { setUrlWithoutDomain(it) }
                    ?: return@mapNotNull null
                title = elm.selectFirst("div.title > h2")?.text()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                elm.selectFirst("div.content-thumb > img")?.attr("src")?.let { thumbnail_url = it }
            }
        }
        val hasNextPage = try {
            val pagination = document.selectFirst("div.pagination")!!
            val totalPage = pagination.selectFirst("span:nth-child(1)")!!.text().split(" ").last()
            val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
            currentPage.toInt() < totalPage.toInt()
        } catch (_: Exception) {
            false
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase()) {
        "completed" -> SAnime.COMPLETED
        "ongoing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    /**
     * Player servers come in three shapes:
     *
     *  - `data-embed` holding escaped iframe markup (Blogspot, filedon, Mega, wibufile)
     *  - `data-embed` holding a WordPress shortcode such as `[vidlion id=dtnwoqholq0x]`, which the
     *    site resolves client side; `vidlion` is VidHide, so the embed is `vidhidepro.com/v/<id>`
     *  - nothing usable, in which case the legacy `admin-ajax.php` call is tried
     */
    private suspend fun getEmbedLinks(url: String, element: Element): Pair<String, String> {
        val server = element.selectFirst("span")?.text()?.trim().orEmpty()
        val embed = element.attr("data-embed").takeIf(String::isNotBlank)

        if (embed != null) {
            VIDLION_SHORTCODE.find(embed)?.let { match ->
                return Pair(server, "$VIDHIDE_BASE/v/${match.groupValues[1]}")
            }

            Jsoup.parse(embed).selectFirst("iframe[src]")
                ?.attr("src")
                ?.takeIf(String::isNotBlank)
                ?.let { return Pair(server, it) }
        }

        val form = FormBody.Builder().apply {
            add("action", "player_ajax")
            add("post", element.attr("data-post"))
            add("nume", element.attr("data-nume"))
            add("type", element.attr("data-type"))
        }.build()

        val ajaxHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .build()

        return client.newCall(POST("$url/wp-admin/admin-ajax.php", body = form, headers = ajaxHeaders))
            .awaitSuccess()
            .bodyString()
            .let {
                val link = srcRegex.find(it)?.groupValues?.get(1)?.takeIf(String::isNotBlank) ?: ""
                Pair(server, link)
            }
    }

    private suspend fun getVideosFromEmbed(server: String, link: String): List<Video> {
        if (link.isBlank()) return emptyList()

        val videoHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .add("Referer", link)
            .build()

        return when {
            "mega.nz" in link || "mega.co.nz" in link ->
                runCatching { megaExtractor.videosFromUrl(link, "$server - ") }.getOrDefault(emptyList())

            "vidhide" in link ->
                runCatching { vidHideExtractor.videosFromUrl(link) { quality -> "$server - $quality" } }
                    .getOrDefault(emptyList())

            "blogger" in link || "blogspot" in link -> bloggerVideos(link, server, videoHeaders)

            link.contains(".mp4") || link.contains(".webm") || link.contains(".m3u8") -> {
                listOf(Video(link, server, link, videoHeaders))
            }

            "filedon" in link || "uservideo" in link || "userdrive" in link || "samevideo" in link -> {
                val doc = client.newCall(GET(link, videoHeaders)).awaitSuccess().useAsJsoup()
                val dataPage = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyList()
                val json = JSONObject(dataPage)
                val props = json.getJSONObject("props")
                val videoUrl = props.getString("url")
                listOf(Video(videoUrl, server, videoUrl, videoHeaders))
            }

            "krakenfiles" in link -> {
                val doc = client.newCall(GET(link, videoHeaders)).awaitSuccess().useAsJsoup()
                val getUrl = doc.selectFirst("source")?.attr("src") ?: return emptyList()
                val videoUrl = UrlUtils.fixUrl(getUrl)?.replace("&amp;", "&") ?: return emptyList()
                listOf(Video(videoUrl, server, videoUrl, videoHeaders))
            }

            else -> {
                val doc = client.newCall(GET(link, videoHeaders)).awaitSuccess().useAsJsoup()
                val videoUrl = doc.selectFirst("video source")?.attr("src") ?: return emptyList()
                val finalUrl = UrlUtils.fixUrl(videoUrl) ?: return emptyList()
                listOf(Video(finalUrl, server, finalUrl, videoHeaders))
            }
        }
    }

    /**
     * `blogger.com/video.g?token=...` no longer ships a `"streams":[{"play_url":...}]` block, so the
     * shared BloggerExtractor falls through to its `WcwnYd` RPC and hands back URLs the player
     * refuses ("loading failed"). The playable `googlevideo.com/videoplayback` URL is still inlined
     * in the page, but inside a JS/JSON blob where `=` and `&` are double-escaped and the quotes are
     * HTML entities, so both layers have to be undone before matching.
     */
    private suspend fun bloggerVideos(link: String, server: String, videoHeaders: Headers): List<Video> {
        val body = client.newCall(GET(link, videoHeaders)).awaitSuccess().bodyString()
        if (body.contains("errorContainer")) return emptyList()

        val unescaped = body
            .replace(JSON_EQUALS, "=")
            .replace(JSON_AMPERSAND, "&")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")

        return GOOGLEVIDEO_REGEX.findAll(unescaped)
            .map(MatchResult::value)
            .filter { "itag=" in it }
            .distinct()
            .map { url -> Video(url, "$server - ${bloggerQuality(url)}", url, videoHeaders) }
            .toList()
    }

    private fun bloggerQuality(url: String): String = when (ITAG_REGEX.find(url)?.groupValues?.get(1)) {
        "18" -> "360p"
        "22" -> "720p"
        "37" -> "1080p"
        else -> "Direct"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            summary = "%s"
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private const val LIST_SELECTOR = "div.relat article"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
        }
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val VIDHIDE_BASE = "https://vidhidepro.com"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private val srcRegex by lazy { Regex("""src\s*=\s*["']([^"']+)["']""") }
        private val VIDLION_SHORTCODE by lazy { Regex("""\[vidlion\s+id=([A-Za-z0-9]+)]""") }

        // The raw page carries `\\u003d`, i.e. one or more backslashes followed by `u003d`.
        private val JSON_EQUALS by lazy { Regex("""\\+u003d""") }
        private val JSON_AMPERSAND by lazy { Regex("""\\+u0026""") }
        private val GOOGLEVIDEO_REGEX by lazy {
            Regex("""https://[^\s"'<>\\]*googlevideo\.com/videoplayback[^\s"'<>\\]*""")
        }
        private val ITAG_REGEX by lazy { Regex("""[?&]itag=(\d+)""") }
    }
}
