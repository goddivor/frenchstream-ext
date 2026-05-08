package eu.kanade.tachiyomi.animeextension.fr.frenchstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class FrenchStream : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "FrenchStream"

    override val lang = "fr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)!!.trimEnd('/')

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/films/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div.short"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.short-poster[href]") ?: element.selectFirst("a[href]")
        // Always init `url` to avoid UninitializedPropertyAccessException downstream
        // (Anikku's "related mangas" feature crashes hard when any SAnime has no url).
        url = link?.attr("href")?.takeIf { it.isNotBlank() }
            ?.let { it.substringAfter("//").substringAfter("/").let { p -> "/$p" } }
            ?: "/"
        title = link?.let { it.attr("alt").ifBlank { it.attr("title") }.trim() }
            ?: element.selectFirst("img")?.attr("alt").orEmpty()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector(): String = "a[href*='page/']"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .filter { it.url.length > 1 && it.title.isNotBlank() }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series/en-cours/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("query", query)
            .add("page", page.toString())
            .build()
        val searchHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", "$baseUrl/")
            .build()
        return POST("$baseUrl/engine/ajax/search.php", searchHeaders, body)
    }

    override fun searchAnimeSelector(): String = "div.search-item"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val onclick = element.attr("onclick")
        val href = Regex("location\\.href=['\"]([^'\"]+)['\"]").find(onclick)?.groupValues?.get(1)
            ?: element.selectFirst("a[href]")?.attr("href")
            ?: ""
        // Always init `url` even if extraction fails - Anikku's related-mangas feature
        // crashes with UninitializedPropertyAccessException otherwise.
        url = href.takeIf { it.isNotBlank() }
            ?.let { it.substringAfter("//").substringAfter("/").let { p -> "/$p" } }
            ?: "/"
        title = element.selectFirst("div.search-title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt").orEmpty()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Skip entries that didn't get a real URL — they would crash the related-mangas feature.
        val animes = document.select(searchAnimeSelector())
            .map(::searchAnimeFromElement)
            .filter { it.url.length > 1 && it.title.isNotBlank() }
        return AnimesPage(animes, false)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        // setUrlWithoutDomain may silently fail when document.location() is empty.
        // Always init url with a sane fallback so Aniyomi doesn't crash with
        // UninitializedPropertyAccessException on lateinit url.
        url = document.location().substringAfter("//").substringAfter("/").let { "/$it" }
            .takeIf { it.length > 1 } ?: "/"
        title = document.selectFirst("h1")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("div.fimg img, .short-poster img")?.absUrl("src")
        description = document.selectFirst("span[id^='desc-']")?.text()
            ?: document.selectFirst(".fdesc")?.text()
        genre = document.select("span.fgenre a, .flist a[href*='genre']").joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    // Override getAnimeDetails so we can propagate the original url from the SAnime
    // we received in case animeDetailsParse can't recover it from the document.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(animeDetailsRequest(anime)).execute()
        val parsed = animeDetailsParse(response.asJsoup())
        // If the parsed url is the placeholder "/", reuse the original anime.url
        if (parsed.url.length <= 1) parsed.url = anime.url
        parsed.initialized = true
        return parsed
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val pageUrl = response.request.url
        val url = pageUrl.toString()

        // Site now uses query strings (?newsid=ID) — try those first, fall back to
        // the old `/ID-slug.html` pattern, and finally to any digits we find on the page.
        val newsId = pageUrl.queryParameter("newsid")
        val pathId = url.substringAfterLast("/").substringBefore("-").takeIf { it.all(Char::isDigit) }
        val pageId = document.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotEmpty() }
            ?: Regex("""ep-data\.php\?id=(\d+)""").find(document.html())?.groupValues?.get(1)
            ?: Regex("""dle_id\s*=\s*['"]?(\d+)""").find(document.html())?.groupValues?.get(1)
        val effectiveId = newsId ?: pageId ?: pathId.orEmpty()

        // Use the host of the page URL itself in case the user landed on a mirror
        // domain (e.g. fs03.lol while baseUrl pref says fs18.lol).
        val pageHost = "${pageUrl.scheme}://${pageUrl.host}"
        val epUrl = "$pageHost/ep-data.php?id=$effectiveId"
        val epResponse = client.newCall(GET(epUrl, headers)).execute()
        val epBody = epResponse.body.string()

        val epData = runCatching { json.decodeFromString<JsonObject>(epBody) }
            .getOrElse { JsonObject(emptyMap()) }

        // Check if it's a film (has "players" key) vs series (has "vf"/"vostfr" keys)
        if (epData.containsKey("players")) {
            return parseFilmEpisodes(epData, effectiveId)
        }

        val vfData = epData["vf"]?.jsonObject
        val vostfrData = epData["vostfr"]?.jsonObject

        if (epBody.isBlank() || epBody == "{}" ||
            (vfData?.isEmpty() != false && vostfrData?.isEmpty() != false)
        ) {
            // Try film API as fallback
            val filmUrl = "$pageHost/engine/ajax/film_api.php?id=$effectiveId"
            val filmResponse = client.newCall(GET(filmUrl, headers)).execute()
            val filmBody = filmResponse.body.string()
            if (filmBody.isNotBlank() && filmBody != "{}") {
                val filmData = runCatching { json.decodeFromString<JsonObject>(filmBody) }
                    .getOrElse { JsonObject(emptyMap()) }
                if (filmData.containsKey("players")) {
                    return parseFilmEpisodes(filmData, effectiveId)
                }
            }
            return emptyList()
        }

        val infoData = epData["info"]?.jsonObject

        // Union de tous les numéros d'épisodes présents en VF et/ou VOSTFR
        val allEpNums = ((vfData?.keys ?: emptySet()) + (vostfrData?.keys ?: emptySet()))
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 0 }

        if (allEpNums.isEmpty()) return emptyList()

        val episodes = allEpNums.map { epNum ->
            val epInfo = infoData?.get(epNum)?.jsonObject
            val epTitle = epInfo?.get("title")?.jsonPrimitive?.content

            val vfPlayers = vfData?.get(epNum)?.jsonObject
            val vostfrPlayers = vostfrData?.get(epNum)?.jsonObject

            // Encode tous les players des deux langues avec un préfixe: "lang#server::url"
            val encodedPlayers = buildList {
                vfPlayers?.entries?.forEach {
                    add("vf#${it.key}::${it.value.jsonPrimitive.content}")
                }
                vostfrPlayers?.entries?.forEach {
                    add("vostfr#${it.key}::${it.value.jsonPrimitive.content}")
                }
            }.joinToString(";;;")

            val langLabel = listOfNotNull(
                "VF".takeIf { vfPlayers?.isNotEmpty() == true },
                "VOSTFR".takeIf { vostfrPlayers?.isNotEmpty() == true },
            ).joinToString(", ")

            SEpisode.create().apply {
                val num = epNum.toIntOrNull() ?: 0
                episode_number = num.toFloat()
                name = if (epTitle != null) {
                    "Épisode $epNum - $epTitle"
                } else {
                    "Épisode $epNum"
                }
                scanlator = langLabel
                this.url = encodedPlayers
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun parseFilmEpisodes(filmData: JsonObject, id: String): List<SEpisode> {
        val players = filmData["players"]?.jsonObject ?: return emptyList()

        // Collect all player URLs from the "default" variant
        val playerUrls = players.entries.mapNotNull { (server, variants) ->
            val varObj = variants.jsonObject
            val url = varObj["default"]?.jsonPrimitive?.content
                ?: varObj.values.firstOrNull()?.jsonPrimitive?.content
            if (url != null) "$server::$url" else null
        }.joinToString(";;;")

        return listOf(
            SEpisode.create().apply {
                name = "Film"
                episode_number = 1f
                this.url = playerUrls
            },
        )
    }

    // ============================ Video Links =============================

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        // Format series: "lang#server::url;;;lang#server::url"
        // Format film : "server::url;;;server::url" (pas de préfixe de langue)
        data class PlayerEntry(val lang: String?, val server: String, val url: String)

        val allEntries = episode.url.split(";;;").filter { it.contains("::") }.map { entry ->
            val (prefix, playerUrl) = entry.split("::", limit = 2)
            if (prefix.contains("#")) {
                val (lang, server) = prefix.split("#", limit = 2)
                PlayerEntry(lang, server, playerUrl)
            } else {
                PlayerEntry(null, prefix, playerUrl)
            }
        }

        // Si l'épisode a des langues taggées, utilise la langue préférée
        // en priorité, sinon fallback sur l'autre langue disponible
        val hasLangTag = allEntries.any { it.lang != null }
        val selectedEntries = if (hasLangTag) {
            val preferred = allEntries.filter { it.lang == prefLang }
            preferred.ifEmpty { allEntries.filter { it.lang != null } }
        } else {
            allEntries
        }

        val videos = mutableListOf<Video>()

        selectedEntries.forEach { entry ->
            val server = entry.server
            val playerUrl = entry.url

            try {
                val extracted = when {
                    server == "uqload" || playerUrl.contains("uqload") ->
                        uqloadExtractor.videosFromUrl(playerUrl)
                    server == "voe" || playerUrl.contains("voe") ->
                        voeExtractor.videosFromUrl(playerUrl)
                    server == "vidzy" || playerUrl.contains("vidzy") ->
                        vidHideExtractor.videosFromUrl(playerUrl) { "$server - $it" }
                    server == "netu" && playerUrl.contains("filemoon") ->
                        filemoonExtractor.videosFromUrl(playerUrl)
                    server == "netu" || playerUrl.contains("kakaflix") || playerUrl.contains("kokoflix") ->
                        voeExtractor.videosFromUrl(playerUrl)
                    server == "premium" || playerUrl.contains("fsvid") ->
                        vidHideExtractor.videosFromUrl(playerUrl) { "Premium - $it" }
                    server == "dood" || playerUrl.contains("dood") || playerUrl.contains("tokyo") ->
                        voeExtractor.videosFromUrl(playerUrl)
                    else -> emptyList()
                }
                videos.addAll(extracted)
            } catch (_: Exception) {
            }
        }

        return videos
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun List<Video>.sort(): List<Video> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.quality.contains(server, ignoreCase = true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Langue préférée"
            entries = arrayOf("VF", "VOSTFR")
            entryValues = arrayOf("vf", "vostfr")
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Lecteur préféré"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(key, selected).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "URL du site"
            summary = "URL actuelle : ${preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)}"
            dialogTitle = "Modifier l'URL du site"
            dialogMessage = "Entrez la nouvelle URL (ex: https://fs03.lol)"
            setDefaultValue(DEFAULT_BASE_URL)

            setOnPreferenceChangeListener { _, newValue ->
                summary = "URL actuelle : $newValue"
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "base_url"
        private const val DEFAULT_BASE_URL = "https://fs03.lol"
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_DEFAULT = "vf"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidzy"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Vidzy",
            "Uqload",
            "VOE",
            "Netu",
        )
    }
}
