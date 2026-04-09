package eu.kanade.tachiyomi.animeextension.fr.frenchstream

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
        val link = element.selectFirst("a.short-poster[href]")
        if (link != null) {
            setUrlWithoutDomain(link.attr("href"))
            title = link.attr("alt").ifBlank { link.attr("title") }.trim()
        }
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector(): String = "a[href*='page/']"

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
        val href = Regex("location\\.href='([^']+)'").find(onclick)?.groupValues?.get(1).orEmpty()
        if (href.isNotEmpty()) setUrlWithoutDomain(href)
        title = element.selectFirst("div.search-title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt").orEmpty()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
        // L'endpoint AJAX renvoie tous les résultats sur une seule page
        return AnimesPage(animes, false)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("div.fimg img, .short-poster img")?.absUrl("src")
        description = document.selectFirst("span[id^='desc-']")?.text()
            ?: document.selectFirst(".fdesc")?.text()
        genre = document.select("span.fgenre a, .flist a[href*='genre']").joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        // Extract ID from URL: /15122868-slug.html -> 15122868
        val id = url.substringAfterLast("/").substringBefore("-")
        Log.d(TAG, "episodeListParse: id=$id, url=$url")

        // Call episode API
        val epResponse = client.newCall(GET("$baseUrl/ep-data.php?id=$id", headers)).execute()
        val epBody = epResponse.body.string()

        val epData = json.decodeFromString<JsonObject>(epBody)

        // Check if it's a film (has "players" key) vs series (has "vf"/"vostfr" keys)
        if (epData.containsKey("players")) {
            Log.d(TAG, "episodeListParse: detected FILM format")
            return parseFilmEpisodes(epData, id)
        }

        val vfData = epData["vf"]?.jsonObject
        val vostfrData = epData["vostfr"]?.jsonObject

        if (epBody.isBlank() || epBody == "{}" ||
            (vfData?.isEmpty() != false && vostfrData?.isEmpty() != false)
        ) {
            // Try film API as fallback
            Log.d(TAG, "episodeListParse: ep-data empty, trying film_api")
            val filmResponse = client.newCall(GET("$baseUrl/engine/ajax/film_api.php?id=$id", headers)).execute()
            val filmBody = filmResponse.body.string()
            if (filmBody.isNotBlank() && filmBody != "{}") {
                val filmData = json.decodeFromString<JsonObject>(filmBody)
                if (filmData.containsKey("players")) {
                    return parseFilmEpisodes(filmData, id)
                }
            }
            Log.w(TAG, "episodeListParse: no data found")
            return emptyList()
        }

        val infoData = epData["info"]?.jsonObject

        // Union de tous les numéros d'épisodes présents en VF et/ou VOSTFR
        val allEpNums = ((vfData?.keys ?: emptySet()) + (vostfrData?.keys ?: emptySet()))
            .distinct()
            .sortedBy { it.toIntOrNull() ?: 0 }

        if (allEpNums.isEmpty()) {
            Log.w(TAG, "episodeListParse: no episodes found in API")
            return emptyList()
        }

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

        Log.d(TAG, "episodeListParse: ${episodes.size} episodes found")
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

        Log.d(TAG, "parseFilmEpisodes: ${players.size} players")

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
            if (preferred.isNotEmpty()) {
                Log.d(TAG, "getVideoList: using preferred lang=$prefLang (${preferred.size} players)")
                preferred
            } else {
                val fallback = allEntries.filter { it.lang != null }
                Log.d(TAG, "getVideoList: $prefLang unavailable, fallback (${fallback.size} players)")
                fallback
            }
        } else {
            allEntries
        }

        Log.d(TAG, "getVideoList: ${selectedEntries.size} players total")

        val videos = mutableListOf<Video>()

        selectedEntries.forEach { entry ->
            val server = entry.server
            val playerUrl = entry.url
            Log.d(TAG, "getVideoList: lang=${entry.lang}, server=$server, url=$playerUrl")

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
                    else -> {
                        Log.w(TAG, "getVideoList: unknown server=$server")
                        emptyList()
                    }
                }
                Log.d(TAG, "getVideoList: $server extracted ${extracted.size} videos")
                videos.addAll(extracted)
            } catch (e: Exception) {
                Log.e(TAG, "getVideoList: error for $server: ${e.message}")
            }
        }

        Log.d(TAG, "getVideoList: total=${videos.size}")
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
            dialogMessage = "Entrez la nouvelle URL (ex: https://fs18.lol)"
            setDefaultValue(DEFAULT_BASE_URL)

            setOnPreferenceChangeListener { _, newValue ->
                summary = "URL actuelle : $newValue"
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val TAG = "FrenchStream"
        private const val PREF_BASE_URL_KEY = "base_url"
        private const val DEFAULT_BASE_URL = "https://fs18.lol"
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
