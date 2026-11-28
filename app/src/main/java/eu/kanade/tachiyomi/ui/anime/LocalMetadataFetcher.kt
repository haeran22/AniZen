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
    ): ResolvedMetadata? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().ifEmpty { DEFAULT_TMDB_API_KEY }
        val isMovie = mediaType == "movie"

        val baseUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", cleanKey)
            .addQueryParameter("language", language)
            .build()
        val data = executeGetJson(baseUrl.toString()) ?: return@withContext null

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
                it.jsonObject["name"]?.jsonPrimitive?.content.isNullOrBlank()
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

        val episodesList = rawEpArray.mapIndexedNotNull { idx, epEl ->
            val epObj = epEl.jsonObject
            val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val fbObj = fallbackEpisodes.getOrNull(idx)
            val epTitle = epObj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: fbObj?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "Episodio $epNum"
            val airDate = epObj["air_date"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { "${it}T00:00:00" }

            EpisodeDetails(
                episode_number = epNum.toFloat(),
                name = "Episodio $epNum - $epTitle",
                season = targetSeason,
                date_upload = airDate,
                summary = null,
            )
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
