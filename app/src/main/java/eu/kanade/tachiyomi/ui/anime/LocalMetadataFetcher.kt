package eu.kanade.tachiyomi.ui.anime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SAnime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.metadata.tachiyomi.EpisodeDetails
import tachiyomi.domain.anime.model.Anime
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.math.max

object LocalMetadataFetcher {

    const val DEFAULT_TMDB_API_KEY = "1bde811060837749f3097f353c9b3a85"
    private const val TVDB_PUBLIC_KEY = "5476e702-85aa-45fd-a8da-e74df3840baf"
    private var cachedTvdbToken: String? = null

    data class TmdbSearchItem(
        val id: Int,
        val mediaType: String,
        val title: String,
        val originalTitle: String,
        val year: String,
        val posterUrl: String?,
    )

    data class TmdbSeasonInfo(
        val seasonNumber: Int,
        val name: String,
        val episodeCount: Int,
    )

    data class ResolvedMetadata(
        val titleRomaji: String,
        val titleEs: String,
        val titleNative: String,
        val studio: String,
        val description: String,
        val genres: List<String>,
        val status: Int,
        val bangumiId: Int?,
        val bangumiCoverUrl: String?,
        val tmdbCoverUrl: String?,
        val seasons: List<TmdbSeasonInfo>,
        val selectedSeason: Int,
        val episodes: List<EpisodeDetails>,
    )

    private data class TvdbEpisodeInfo(
        val name: String?,
        val overview: String?,
        val aired: String?,
    )

    private val client by lazy { Injekt.get<NetworkHelper>().client }
    private val fileSystem by lazy { Injekt.get<LocalAnimeSourceFileSystem>() }
    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = false
        }
    }

    suspend fun searchTmdb(
        query: String,
        mediaType: String,
        language: String,
        apiKey: String = DEFAULT_TMDB_API_KEY,
    ): List<TmdbSearchItem> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().ifEmpty { DEFAULT_TMDB_API_KEY }
        val url = "https://api.themoviedb.org/3/search/$mediaType".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", cleanKey)
            .addQueryParameter("language", language)
            .addQueryParameter("query", query.trim())
            .addQueryParameter("include_adult", "false")
            .build()

        val root = executeGetJson(url.toString()) ?: return@withContext emptyList()
        val results = root["results"]?.jsonArray ?: return@withContext emptyList()

        results.take(15).mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val isTv = mediaType == "tv"
            val title = obj[if (isTv) "name" else "title"]?.jsonPrimitive?.content.orEmpty()
            val orig = obj[if (isTv) "original_name" else "original_title"]?.jsonPrimitive?.content.orEmpty()
            val date = obj[if (isTv) "first_air_date" else "release_date"]?.jsonPrimitive?.content.orEmpty()
            val posterPath = obj["poster_path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }

            TmdbSearchItem(
                id = id,
                mediaType = mediaType,
                title = title,
                originalTitle = orig,
                year = if (date.length >= 4) date.substring(0, 4) else "—",
                posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            )
        }
    }

    suspend fun fetchFullMetadata(
        tmdbId: Int,
        mediaType: String,
        language: String,
        seasonNumber: Int?,
        manualBangumiQuery: String? = null,
        apiKey: String = DEFAULT_TMDB_API_KEY,
        preferTvdbEpisodes: Boolean = true,
    ): ResolvedMetadata? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().ifEmpty { DEFAULT_TMDB_API_KEY }
        val isMovie = mediaType == "movie"

        val baseUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", cleanKey)
            .addQueryParameter("language", language)
            .addQueryParameter("append_to_response", "external_ids")
            .build()
        val data = executeGetJson(baseUrl.toString()) ?: return@withContext null

        val tvdbId = data["external_ids"]?.jsonObject?.get("tvdb_id")?.jsonPrimitive?.intOrNull
        val esTitle = data[if (isMovie) "title" else "name"]?.jsonPrimitive?.content.orEmpty()
        val origTitle = data[if (isMovie) "original_title" else "original_name"]?.jsonPrimitive?.content.orEmpty()
        val genres = data["genres"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }.orEmpty()

        var overview = data["overview"]?.jsonPrimitive?.content.orEmpty()
        if (overview.isBlank() && language != "es-ES") {
            val fbUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", cleanKey)
                .addQueryParameter("language", "es-ES")
                .build()
            overview = executeGetJson(fbUrl.toString())?.get("overview")?.jsonPrimitive?.content.orEmpty()
        }

        val tmdbStudios = if (isMovie) {
            data["production_companies"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }?.joinToString(", ").orEmpty()
        } else {
            (data["production_companies"]?.jsonArray ?: data["networks"]?.jsonArray)
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                ?.joinToString(", ").orEmpty()
        }

        val baseStatus = mapStatusToAniZen(data["status"]?.jsonPrimitive?.content, isMovie)
        val seriesPoster = data["poster_path"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/original$it" }

        val validSeasons = if (!isMovie) {
            data["seasons"]?.jsonArray?.mapNotNull { sEl ->
                val sObj = sEl.jsonObject
                val sNum = sObj["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val epCount = sObj["episode_count"]?.jsonPrimitive?.intOrNull ?: 0
                if (epCount <= 0) return@mapNotNull null
                TmdbSeasonInfo(
                    seasonNumber = sNum,
                    name = sObj["name"]?.jsonPrimitive?.content ?: "Temporada $sNum",
                    episodeCount = epCount,
                )
            }.orEmpty()
        } else {
            emptyList()
        }

        val targetSeason = if (isMovie) {
            1
        } else {
            seasonNumber?.takeIf { s -> validSeasons.any { it.seasonNumber == s } }
                ?: validSeasons.firstOrNull { it.seasonNumber == 1 }?.seasonNumber
                ?: validSeasons.firstOrNull()?.seasonNumber
                ?: 1
        }

        val baseAni = fetchAniListMedia(listOf(origTitle, esTitle))
        val baseRomaji = baseAni?.romaji ?: origTitle.ifBlank { esTitle }
        val baseNative = baseAni?.native ?: origTitle
        val baseAniStudio = baseAni?.studio.orEmpty()

        if (isMovie || validSeasons.isEmpty()) {
            val bgmQueries = if (!manualBangumiQuery.isNullOrBlank()) {
                listOf(manualBangumiQuery)
            } else {
                listOf(baseNative, origTitle, baseRomaji)
            }
            val bgmHit = searchBangumiCover(bgmQueries)

            return@withContext ResolvedMetadata(
                titleRomaji = baseRomaji,
                titleEs = esTitle,
                titleNative = baseNative,
                studio = baseAniStudio.ifBlank { tmdbStudios },
                description = overview,
                genres = genres,
                status = baseStatus,
                bangumiId = bgmHit?.first,
                bangumiCoverUrl = bgmHit?.second,
                tmdbCoverUrl = seriesPoster,
                seasons = emptyList(),
                selectedSeason = 1,
                episodes = emptyList(),
            )
        }

        val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$targetSeason".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", cleanKey)
            .addQueryParameter("language", language)
            .build()
        val seasonData = executeGetJson(seasonUrl.toString())

        var seasonOverview = seasonData?.get("overview")?.jsonPrimitive?.content.orEmpty()
        var fallbackEpisodes = emptyList<JsonObject>()
        val rawEpArray = seasonData?.get("episodes")?.jsonArray.orEmpty()

        if (language != "es-ES" && (seasonOverview.isBlank() || rawEpArray.any {
                isGenericEpisodeTitle(it.jsonObject["name"]?.jsonPrimitive?.content)
            })
        ) {
            val esSeasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$targetSeason".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", cleanKey)
                .addQueryParameter("language", "es-ES")
                .build()
            val esSeasonData = executeGetJson(esSeasonUrl.toString())
            if (seasonOverview.isBlank()) {
                seasonOverview = esSeasonData?.get("overview")?.jsonPrimitive?.content.orEmpty()
            }
            fallbackEpisodes = esSeasonData?.get("episodes")?.jsonArray?.map { it.jsonObject }.orEmpty()
        }

        val needsTvdb = preferTvdbEpisodes || rawEpArray.isEmpty() || rawEpArray.any { epEl ->
            val primaryName = epEl.jsonObject["name"]?.jsonPrimitive?.content
            isGenericEpisodeTitle(primaryName)
        }
        val tvdbEpisodesMap = if (needsTvdb) {
            fetchTvdbEpisodesSpanish(
                tvdbId = tvdbId,
                queries = listOf(esTitle, origTitle, baseRomaji),
                seasonNumber = targetSeason,
            )
        } else {
            emptyMap()
        }

        val seasonPoster = seasonData?.get("poster_path")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/original$it" }
            ?: seriesPoster

        val seasonName = seasonData?.get("name")?.jsonPrimitive?.content
            ?: validSeasons.firstOrNull { it.seasonNumber == targetSeason }?.name.orEmpty()

        val seasonAni = resolveSeasonAniList(baseAni, targetSeason, seasonName)
        val seasonRomaji = seasonAni?.romaji
            ?: if (targetSeason > 1) "$baseRomaji Season $targetSeason" else baseRomaji
        val seasonNative = seasonAni?.native
            ?: if (targetSeason > 1) "$baseNative 第${targetSeason}期" else baseNative
        val seasonEs = if (targetSeason > 1 && seasonName.isNotBlank() && !Regex("""(?i)^temporada\s*\d+$""").matches(seasonName.trim())) {
            "$esTitle: $seasonName"
        } else if (targetSeason > 1) {
            "$esTitle - Temporada $targetSeason"
        } else {
            esTitle
        }
        val seasonStudio = seasonAni?.studio?.takeIf { it.isNotBlank() } ?: baseAniStudio.ifBlank { tmdbStudios }

        val maxSeason = validSeasons.filter { it.seasonNumber > 0 }.maxOfOrNull { it.seasonNumber } ?: targetSeason
        val seasonStatus = if (targetSeason in 1 until maxSeason) SAnime.COMPLETED else baseStatus

        val bgmQueries = if (!manualBangumiQuery.isNullOrBlank()) {
            listOf(manualBangumiQuery)
        } else if (targetSeason <= 1) {
            listOf(seasonNative, origTitle, seasonRomaji)
        } else {
            listOf(
                seasonNative,
                "$baseNative 第${targetSeason}期",
                "$baseNative $targetSeason",
                seasonRomaji,
                "$baseRomaji $targetSeason",
                "$baseRomaji Season $targetSeason",
            )
        }
        val bgmHit = searchBangumiCover(bgmQueries)

        val episodesList = if (rawEpArray.isNotEmpty()) {
            rawEpArray.mapIndexedNotNull { idx, epEl ->
                val epObj = epEl.jsonObject
                val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
                val fbObj = fallbackEpisodes.getOrNull(idx)
                val tmdbTitle = epObj["name"]?.jsonPrimitive?.content?.takeIf { !isGenericEpisodeTitle(it) }
                    ?: fbObj?.get("name")?.jsonPrimitive?.content?.takeIf { !isGenericEpisodeTitle(it) }
                val tvdbInfo = tvdbEpisodesMap[epNum]
                val tvdbTitle = tvdbInfo?.name?.takeIf { !isGenericEpisodeTitle(it) }

                val finalTitle = if (preferTvdbEpisodes) {
                    tvdbTitle ?: tmdbTitle ?: "Episodio $epNum"
                } else {
                    tmdbTitle ?: tvdbTitle ?: "Episodio $epNum"
                }

                val tmdbSummary = epObj["overview"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: fbObj?.get("overview")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val finalSummary = tvdbInfo?.overview?.takeIf { it.isNotBlank() } ?: tmdbSummary

                val airDate = epObj["air_date"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { "${it}T00:00:00" }
                    ?: tvdbInfo?.aired?.takeIf { it.isNotBlank() }?.let { "${it}T00:00:00" }

                EpisodeDetails(
                    episode_number = epNum.toFloat(),
                    name = finalTitle,
                    season = targetSeason,
                    date_upload = airDate,
                    summary = finalSummary,
                )
            }
        } else {
            tvdbEpisodesMap.entries.sortedBy { it.key }.map { (epNum, info) ->
                EpisodeDetails(
                    episode_number = epNum.toFloat(),
                    name = info.name?.takeIf { !isGenericEpisodeTitle(it) } ?: "Episodio $epNum",
                    season = targetSeason,
                    date_upload = info.aired?.takeIf { it.isNotBlank() }?.let { "${it}T00:00:00" },
                    summary = info.overview?.takeIf { it.isNotBlank() },
                )
            }
        }

        ResolvedMetadata(
            titleRomaji = seasonRomaji,
            titleEs = seasonEs,
            titleNative = seasonNative,
            studio = seasonStudio,
            description = seasonOverview.ifBlank { overview },
            genres = genres,
            status = seasonStatus,
            bangumiId = bgmHit?.first,
            bangumiCoverUrl = bgmHit?.second,
            tmdbCoverUrl = seasonPoster,
            seasons = validSeasons,
            selectedSeason = targetSeason,
            episodes = episodesList,
        )
    }

    private fun isGenericEpisodeTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val trimmed = title.trim()
        return Regex("""(?i)^(episodio|episode|cap[ií]tulo|ep\.?)\s*\d+$""").matches(trimmed) ||
            trimmed.equals("TBA", ignoreCase = true) ||
            trimmed.equals("To Be Announced", ignoreCase = true)
    }

    private fun getTvdbToken(): String? {
        cachedTvdbToken?.let { return it }
        val payload = buildJsonObject {
            put("apikey", TVDB_PUBLIC_KEY)
        }.toString()
        val req = Request.Builder()
.url("https://" + "api4." + "thetvdb." + "com/v4/login")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .build()
        val respJson = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body.string()).jsonObject
            }
        }.getOrNull() ?: return null
        val token = respJson["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (token != null) {
            cachedTvdbToken = token
        }
        return token
    }

    private fun fetchTvdbEpisodesSpanish(
        tvdbId: Int?,
        queries: List<String>,
        seasonNumber: Int,
    ): Map<Int, TvdbEpisodeInfo> {
        val token = getTvdbToken() ?: return emptyMap()
        val resolvedSeriesId = tvdbId ?: searchTvdbSeriesId(token, queries) ?: return emptyMap()

val url = ("https://" + "api4." + "thetvdb." + "com/v4/series/$resolvedSeriesId/episodes/official/spa").toHttpUrl().newBuilder()
            .addQueryParameter("page", "0")
            .addQueryParameter("season", seasonNumber.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        val respJson = runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    cachedTvdbToken = null
                }
                if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body.string()).jsonObject
            }
        }.getOrNull() ?: return emptyMap()

        val episodesArray = respJson["data"]?.jsonObject?.get("episodes")?.jsonArray
            ?: respJson["data"]?.jsonArray
            ?: return emptyMap()

        val result = mutableMapOf<Int, TvdbEpisodeInfo>()
        for (el in episodesArray) {
            val obj = el.jsonObject
            val sNum = obj["seasonNumber"]?.jsonPrimitive?.intOrNull ?: seasonNumber
            if (sNum != seasonNumber) continue
            val epNum = obj["number"]?.jsonPrimitive?.intOrNull ?: continue
            val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            val overview = obj["overview"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            val aired = obj["aired"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            if (name != null || overview != null) {
                result[epNum] = TvdbEpisodeInfo(name = name, overview = overview, aired = aired)
            }
        }
        return result
    }

    private fun searchTvdbSeriesId(token: String, queries: List<String>): Int? {
        val seen = mutableSetOf<String>()
        for (raw in queries) {
            val q = raw.trim()
            if (q.isEmpty() || !seen.add(q)) continue
val url = ("https://" + "api4." + "thetvdb." + "com/v4/search").toHttpUrl().newBuilder()
                .addQueryParameter("query", q)
                .addQueryParameter("type", "series")
                .addQueryParameter("limit", "5")
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            val respJson = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body.string()).jsonObject
                }
            }.getOrNull() ?: continue

            val firstHit = respJson["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            val tvdbId = firstHit["tvdb_id"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: firstHit["id"]?.jsonPrimitive?.content?.substringAfterLast("-")?.toIntOrNull()
            if (tvdbId != null) return tvdbId
        }
        return null
    }
         suspend fun saveDetailsJson(
        anime: Anime,
        title: String?,
        author: String?,
        artist: String?,
        description: String?,
        genres: List<String>?,
        status: Int?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val animeDir = fileSystem.getAnimeDirectory(anime.url)
                ?: throw IllegalStateException("No se encontró la carpeta local del anime: ${anime.url}")

            val detailsObj = buildJsonObject {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                author?.takeIf { it.isNotBlank() }?.let { put("author", it) }
                artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
                description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                if (!genres.isNullOrEmpty()) {
                    putJsonArray("genre") {
                        genres.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) }
                    }
                }
                status?.let { put("status", it) }
            }

            val jsonText = json.encodeToString(detailsObj)
            writeBytesToUniFile(animeDir, "details.json", jsonText.toByteArray(Charsets.UTF_8))
            "details.json guardado en ${animeDir.name ?: anime.url}"
        }
    }

    suspend fun saveFilesToAnimeDir(
        anime: Anime,
        episodes: List<EpisodeDetails>,
        coverUrl: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val animeDir = fileSystem.getAnimeDirectory(anime.url)
                ?: throw IllegalStateException("No se encontró la carpeta local del anime: ${anime.url}")

            if (episodes.isNotEmpty()) {
                val jsonText = json.encodeToString(episodes)
                writeBytesToUniFile(animeDir, "episodes.json", jsonText.toByteArray(Charsets.UTF_8))
            }

            if (!coverUrl.isNullOrBlank()) {
                val coverBytes = downloadAndResizeCoverTo680x1000(coverUrl)
                if (coverBytes != null) {
                    writeBytesToUniFile(animeDir, "cover.data", coverBytes)
                }
            }

            "Guardado en ${animeDir.name ?: anime.url}"
        }
    }

    private fun writeBytesToUniFile(dir: UniFile, filename: String, bytes: ByteArray) {
        dir.findFile(filename)?.delete()
        val target = dir.createFile(filename)
            ?: throw IllegalStateException("No se pudo crear $filename en ${dir.name}")
        target.openOutputStream().use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    private fun downloadAndResizeCoverTo680x1000(imageUrl: String): ByteArray? {
        val req = Request.Builder()
            .url(imageUrl)
            .header("User-Agent", "AniZen/1.0")
            .build()
        val rawBytes = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body.bytes()
        }
        val srcBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

        val targetW = 680
        val targetH = 1000
        val outBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        val scale = max(targetW.toFloat() / srcBitmap.width, targetH.toFloat() / srcBitmap.height)
        val drawW = srcBitmap.width * scale
        val drawH = srcBitmap.height * scale
        val offsetX = (targetW - drawW) / 2f
        val offsetY = (targetH - drawH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(srcBitmap, null, RectF(offsetX, offsetY, offsetX + drawW, offsetY + drawH), paint)
        srcBitmap.recycle()

        val baos = ByteArrayOutputStream()
        outBitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        outBitmap.recycle()
        return baos.toByteArray()
    }

    private data class AniListNode(
        val romaji: String?,
        val native: String?,
        val studio: String?,
        val sequelRomaji: String?,
    )

    private fun fetchAniListMedia(queries: List<String>): AniListNode? {
        val gql = """
query (${'$'}search: String) {
  Media(search: ${'$'}search, type: ANIME) {
                title { romaji native }
                studios(isMain: true) { nodes { name } }
                relations {
                  edges {
                    relationType
                    node {
                      type
                      format
                      title { romaji }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        for (q in queries) {
            val clean = q.trim()
            if (clean.isEmpty()) continue
            val payload = buildJsonObject {
                put("query", gql)
                putJsonObject("variables") { put("search", clean) }
            }.toString()

            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .build()

            val respJson = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body.string()).jsonObject
                }
            }.getOrNull() ?: continue

            val media = respJson["data"]?.jsonObject?.get("Media")?.jsonObject ?: continue
            val titleObj = media["title"]?.jsonObject
            val romaji = titleObj?.get("romaji")?.jsonPrimitive?.content
            val native = titleObj?.get("native")?.jsonPrimitive?.content
            val studio = media["studios"]?.jsonObject?.get("nodes")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content

            val sequel = media["relations"]?.jsonObject?.get("edges")?.jsonArray
                ?.firstOrNull { edgeEl ->
                    val e = edgeEl.jsonObject
                    val rel = e["relationType"]?.jsonPrimitive?.content
                    val node = e["node"]?.jsonObject
                    val type = node?.get("type")?.jsonPrimitive?.content
                    val format = node?.get("format")?.jsonPrimitive?.content
                    rel == "SEQUEL" && type == "ANIME" && (format == "TV" || format == "ONA")
                }?.jsonObject?.get("node")?.jsonObject?.get("title")?.jsonObject?.get("romaji")?.jsonPrimitive?.content

            return AniListNode(romaji, native, studio, sequel)
        }
        return null
    }

    private fun resolveSeasonAniList(base: AniListNode?, seasonNumber: Int, seasonName: String): AniListNode? {
        if (base == null || seasonNumber <= 1) return base
        var current: AniListNode = base
        for (step in 2..seasonNumber) {
            val nextTitle = current.sequelRomaji ?: break
            val nextNode = fetchAniListMedia(listOf(nextTitle)) ?: break
            current = nextNode
        }
        if (current != base) return current

        val r = base.romaji.orEmpty()
        val n = base.native.orEmpty()
        return fetchAniListMedia(listOf("$r $seasonNumber", "$r Season $seasonNumber", "$n $seasonNumber", seasonName)) ?: base
    }

    private fun searchBangumiCover(queries: List<String>): Pair<Int, String>? {
        val seen = mutableSetOf<String>()
        for (raw in queries) {
            val q = raw.trim()
            if (q.isEmpty() || !seen.add(q)) continue

            if (q.all { it.isDigit() }) {
                val subj = executeGetJson("https://api.bgm.tv/v0/subjects/$q") ?: continue
                val id = subj["id"]?.jsonPrimitive?.intOrNull ?: continue
                val images = subj["images"]?.jsonObject
                val img = images?.get("large")?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: images?.get("common")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (img != null) return id to img
            } else {
                val body = buildJsonObject {
                    put("keyword", q)
                    putJsonObject("filter") {
                        putJsonArray("type") { add(JsonPrimitive(2)) }
                    }
                }.toString()

                val req = Request.Builder()
                    .url("https://api.bgm.tv/v0/search/subjects?limit=6")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", "AniZen/1.0 (https://github.com/haeran22/AniZen)")
                    .build()

                val root = runCatching {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body.string()).jsonObject
                    }
                }.getOrNull() ?: continue

                val hit = root["data"]?.jsonArray?.firstOrNull { itemEl ->
                    val imgs = itemEl.jsonObject["images"]?.jsonObject
                    !imgs?.get("large")?.jsonPrimitive?.content.isNullOrBlank() ||
                        !imgs?.get("common")?.jsonPrimitive?.content.isNullOrBlank()
                }?.jsonObject ?: continue

                val id = hit["id"]?.jsonPrimitive?.intOrNull ?: continue
                val imgs = hit["images"]?.jsonObject
                val img = imgs?.get("large")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: imgs?.get("common")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (img != null) return id to img
            }
        }
        return null
    }

    private fun executeGetJson(url: String): JsonObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AniZen/1.0 (https://github.com/haeran22/AniZen)")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else json.parseToJsonElement(resp.body.string()).jsonObject
            }
        }.getOrNull()
    }

    private fun mapStatusToAniZen(status: String?, isMovie: Boolean): Int {
        if (isMovie) return SAnime.COMPLETED
        return when (status?.lowercase()) {
            "returning series", "in production", "planned" -> SAnime.ONGOING
            "ended" -> SAnime.COMPLETED
            "canceled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }
}
