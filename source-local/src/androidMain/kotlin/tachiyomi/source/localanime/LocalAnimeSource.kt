package tachiyomi.source.localanime

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import rx.Observable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.AnimeDetails
import tachiyomi.core.metadata.tachiyomi.EpisodeDetails
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.service.EpisodeRecognition
import tachiyomi.i18n.MR
import tachiyomi.source.localanime.filter.OrderBy
import tachiyomi.source.localanime.image.LocalAnimeSourceCoverManager
import tachiyomi.source.localanime.image.LocalAnimeSourceEpisodeThumbnailManager
import tachiyomi.source.localanime.io.Archive
import tachiyomi.source.localanime.io.LocalAnimeSourceFileSystem
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import tachiyomi.domain.source.model.Source as DomainSource

actual class LocalAnimeSource(
    private val context: Context,
    val fileSystem: LocalAnimeSourceFileSystem,
    private val coverManager: LocalAnimeSourceCoverManager,
    private val thumbnailManager: LocalAnimeSourceEpisodeThumbnailManager,
    override val id: Long = ID,
    override val name: String = context.stringResource(MR.strings.local_anime_source),
) : CatalogueSource, UnmeteredSource {

    private val json: Json by injectLazy()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val lang = "other"

    override fun toString() = name

    override val supportsLatest = true

        private fun extractSeasonNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = SEASON_REGEX.find(text) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun getSeasonSubdirectories(animeUrl: String): List<UniFile> {
        return fileSystem.getFilesInAnimeDirectory(animeUrl)
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .sortedWith { d1, d2 ->
                val s1 = extractSeasonNumber(d1.name)
                val s2 = extractSeasonNumber(d2.name)
                when {
                    s1 != null && s2 != null && s1 != s2 -> s1.compareTo(s2)
                    s1 != null && s2 == null -> -1
                    s1 == null && s2 != null -> 1
                    else -> d1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(d2.name.orEmpty())
                }
            }
    }

        private fun findCoverUri(dirUrl: String): String? {
        coverManager.find(dirUrl)?.let { return it.uri.toString() }
        val validNames = setOf("cover", "poster", "folder")
        val files = fileSystem.getFilesInAnimeDirectory(dirUrl)
        val coverFile = files.firstOrNull {
            it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true)
        } ?: files.firstOrNull {
            it.isFile && validNames.contains(it.nameWithoutExtension?.lowercase())
        }
        return coverFile?.uri?.toString()
    }

    // Browse related
    override suspend fun getPopularAnime(page: Int) = getSearchAnime(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchAnime(page, "", LatestFilters)

        override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: FilterList,
    ): AnimesPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }

        var animeDirs = fileSystem.getFilesInBaseDirectory()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    animeDirs = if (filter.state!!.ascending) {
                        animeDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        animeDirs.sortedWith(
                            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() },
                        )
                    }
                }

                is OrderBy.Latest -> {
                    animeDirs = if (filter.state!!.ascending) {
                        animeDirs.sortedBy(UniFile::lastModified)
                    } else {
                        animeDirs.sortedByDescending(UniFile::lastModified)
                    }
                }

                else -> {
                    /* Do nothing */
                }
            }
        }

        val animes = animeDirs
            .map { animeDir ->
                async {
                    val dirName = animeDir.name.orEmpty()
                    val dirFiles = animeDir.listFiles().orEmpty().toList()
                    val hasSeasonFolders = dirFiles.any { it.isDirectory && !it.name.orEmpty().startsWith('.') }

                    val coverFile = dirFiles.firstOrNull {
                        it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true)
                    } ?: dirFiles.firstOrNull {
                        it.isFile && (
                            it.nameWithoutExtension.equals("poster", ignoreCase = true) ||
                                it.nameWithoutExtension.equals("folder", ignoreCase = true)
                            )
                    }

                    SAnime.create().apply {
                        title = dirName
                        url = dirName
                        fetch_type = if (hasSeasonFolders) FetchType.Seasons else FetchType.Episodes
                        thumbnail_url = coverFile?.uri?.toString() ?: coverManager.find(dirName)?.uri?.toString()

                        dirFiles
                            .firstOrNull {
                                it.extension == "json" &&
                                    (it.nameWithoutExtension == "details" || it.nameWithoutExtension == "info")
                            }
                            ?.let { file ->
                                runCatching {
                                    json.decodeFromStream<AnimeDetails>(file.openInputStream()).run {
                                        title?.let { this@apply.title = it }
                                        author?.let { this@apply.author = it }
                                        artist?.let { this@apply.artist = it }
                                        description?.let { this@apply.description = it }
                                        genre?.let { this@apply.genre = it.joinToString() }
                                        status?.let { this@apply.status = it }
                                    }
                                }
                            }
                    }
                }
            }
            .awaitAll()

        AnimesPage(animes.toList(), false)
    }


    // Old fetch functions

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopular"))
    override fun fetchPopularAnime(page: Int) = fetchSearchAnime(page, "", PopularFilters)

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int) = fetchSearchAnime(page, "", LatestFilters)

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: FilterList): Observable<AnimesPage> {
        return runBlocking {
            Observable.just(getSearchAnime(page, query, filters))
        }
    }

    fun updateAnimeInfo(anime: SAnime) {
        val directory = fileSystem.getAnimeDirectory(anime.url)?.filePath?.let { File(it) }
            ?.takeIf { it.exists() } ?: return
        val existingFileName = directory.listFiles()?.find { it.extension == "json" && it.name != "episodes.json" }?.name
        val file = File(directory, existingFileName ?: "details.json")
        file.outputStream().use {
            json.encodeToStream(anime.toJson(), it)
        }
    }

    private fun SAnime.toJson(): AnimeDetails {
        return AnimeDetails(title, author, artist, description, genre?.split(", "), status)
    }

        // Anime details related
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withIOContext {
        findCoverUri(anime.url)?.let {
            anime.thumbnail_url = it
        }

        val isRootAnime = !anime.url.contains('/')
        val hasSeasonFolders = isRootAnime && getSeasonSubdirectories(anime.url).isNotEmpty()
        anime.fetch_type = if (hasSeasonFolders) FetchType.Seasons else FetchType.Episodes

        if (!isRootAnime) {
            val folderName = anime.url.substringAfterLast('/')
            extractSeasonNumber(folderName)?.let {
                anime.season_number = it.toDouble()
            }
        }

        val animeDirFiles = fileSystem.getFilesInAnimeDirectory(anime.url)

        animeDirFiles
            .firstOrNull { it.extension == "json" && (it.nameWithoutExtension == "details" || it.nameWithoutExtension == "info") }
            ?.let { file ->
                runCatching {
                    json.decodeFromStream<AnimeDetails>(file.openInputStream()).run {
                        title?.let { anime.title = it }
                        author?.let { anime.author = it }
                        artist?.let { anime.artist = it }
                        description?.let { anime.description = it }
                        genre?.let { anime.genre = it.joinToString() }
                        status?.let { anime.status = it }
                    }
                }
            }

        return@withIOContext anime
    }



    // Seasons (subfolders inside anime directory, Movie-style)
        override suspend fun getSeasonList(anime: SAnime): List<SAnime> = withIOContext {
        val seasonDirs = getSeasonSubdirectories(anime.url)
        if (seasonDirs.isEmpty()) return@withIOContext emptyList()

        seasonDirs.mapIndexed { index, seasonDir ->
            val folderName = seasonDir.name.orEmpty()
            val seasonUrl = "${anime.url}/$folderName"
            val detectedSeasonNum = extractSeasonNumber(folderName) ?: (index + 1)

            SAnime.create().apply {
                url = seasonUrl
                title = folderName
                fetch_type = FetchType.Episodes
                season_number = detectedSeasonNum.toDouble()
                thumbnail_url = findCoverUri(seasonUrl) ?: anime.thumbnail_url
                initialized = true

                // Optional per-season details.json
                fileSystem.getFilesInAnimeDirectory(seasonUrl)
                    .firstOrNull {
                        it.extension == "json" &&
                            (it.nameWithoutExtension == "details" || it.nameWithoutExtension == "info")
                    }
                    ?.let { detailsFile ->
                        runCatching {
                            json.decodeFromStream<AnimeDetails>(detailsFile.openInputStream()).run {
                                title?.let { this@apply.title = it }
                                author?.let { this@apply.author = it }
                                artist?.let { this@apply.artist = it }
                                description?.let { this@apply.description = it }
                                genre?.let { this@apply.genre = it.joinToString() }
                                status?.let { this@apply.status = it }
                            }
                        }
                    }
            }
        }
    }

    // Episodes
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withIOContext {
        val parentAnimeUrl = anime.url.substringBefore('/', "")
        val seasonFromFolder = if (anime.url.contains('/')) {
            extractSeasonNumber(anime.url.substringAfterLast('/'))
        } else {
            null
        }

        // Load local episodes.json (either in season folder or fallback to root anime folder)
        val localEpisodesData = fileSystem.getFilesInAnimeDirectory(anime.url)
            .firstOrNull { it.extension == "json" && it.nameWithoutExtension == "episodes" }
            ?.let { file ->
                runCatching { json.decodeFromStream<List<EpisodeDetails>>(file.openInputStream()) }.getOrNull()
            }

        val rootEpisodesData = if (localEpisodesData == null && parentAnimeUrl.isNotEmpty()) {
            fileSystem.getFilesInAnimeDirectory(parentAnimeUrl)
                .firstOrNull { it.extension == "json" && it.nameWithoutExtension == "episodes" }
                ?.let { file ->
                    runCatching { json.decodeFromStream<List<EpisodeDetails>>(file.openInputStream()) }.getOrNull()
                }
        } else {
            null
        }

        val episodesData = localEpisodesData ?: rootEpisodesData

        val episodes = fileSystem.getFilesInAnimeDirectory(anime.url)
            .filter { Archive.isSupported(it) }
            .map { episodeFile ->
                SEpisode.create().apply {
                    url = "${anime.url}/${episodeFile.name}"
                    name = episodeFile.nameWithoutExtension.orEmpty()
                    date_upload = episodeFile.lastModified()

                    val detectedSeason = extractSeasonNumber(this.name) ?: seasonFromFolder
                    val episodeNumber = EpisodeRecognition.parseEpisodeNumber(
                        anime.title,
                        this.name,
                        this.episode_number.toDouble(),
                    ).toFloat()
                    episode_number = episodeNumber

                    // Overwrite data from episodes.json matching both season and episode number
                    episodesData?.also { dataList ->
                        val matchedData = if (detectedSeason != null) {
                            dataList.firstOrNull { entry ->
                                val entrySeason = entry.resolvedSeason ?: extractSeasonNumber(entry.name)
                                entry.episode_number.equalsTo(episodeNumber) && entrySeason == detectedSeason
                            } ?: if (localEpisodesData != null) {
                                // Inside a season-specific episodes.json, allow entries without explicit season tag
                                dataList.firstOrNull { entry ->
                                    val entrySeason = entry.resolvedSeason ?: extractSeasonNumber(entry.name)
                                    entry.episode_number.equalsTo(episodeNumber) && entrySeason == null
                                }
                            } else {
                                null
                            }
                        } else {
                            dataList.firstOrNull { entry ->
                                val entrySeason = entry.resolvedSeason ?: extractSeasonNumber(entry.name)
                                entry.episode_number.equalsTo(episodeNumber) && (entrySeason == null || entrySeason == 1)
                            }
                        }

                        matchedData?.also { data ->
                            data.name?.also { name = it }
                            data.date_upload?.also { date_upload = parseDate(it) }
                            scanlator = data.scanlator
                            summary = data.summary
                            data.preview_url?.also { preview_url = it }
                        }
                    }

                    // Look for local episode thumbnail file only (video extraction disabled)
                    if (this.preview_url == null) {
                        val thumbnailFile = thumbnailManager.find(anime.url, this.name)
                        if (thumbnailFile != null) {
                            this.preview_url = thumbnailFile.uri.toString()
                        }
                    }
                }
            }
            .sortedWith { e1, e2 ->
                val e = e2.episode_number.compareTo(e1.episode_number)
                if (e == 0) e2.name.compareToCaseInsensitiveNaturalOrder(e1.name) else e
            }

        // Generate the cover from the first episode found if not available
            /*
        // Generate the cover from the first episode found if not available
        if (anime.thumbnail_url == null) {
            scope.launch {
                try {
                    episodes.lastOrNull()?.let { episode ->
                        updateCoverFromVideo(episode, anime)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Couldn't extract thumbnail from video: $e" }
                }
            }
        }
        */

        episodes
    }

    private fun parseDate(isoDate: String): Long {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(isoDate)?.time ?: 0L
    }

    private fun Float.equalsTo(other: Float): Boolean {
        return abs(this - other) < 0.0001
    }

    // Filters
    override fun getFilterList() = FilterList(OrderBy.Popular(context))

    // Unused stuff
    override suspend fun getVideoList(episode: SEpisode): List<Video> = throw UnsupportedOperationException("Unused")

    private fun updateCoverFromVideo(episode: SEpisode, anime: SAnime) {
        val tempFile = File.createTempFile(
            "tmp_",
            anime.title.replace('/', '_') + DEFAULT_COVER_NAME,
        )
        val outFile = tempFile.path

        val episodeName = episode.url.substringAfterLast('/')
        val animeDir = fileSystem.getAnimeDirectory(anime.url) ?: return
        val episodeFile = animeDir.findFile(episodeName) ?: return
        val episodeFilename = { episodeFile.toFFmpegString(context) }

          val ffProbe = com.arthenica.ffmpegkit.FFprobeKit.execute(
            """-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "${episodeFilename()}"""",
        )
        val duration = ffProbe.getAllLogsAsString().trim().toFloat()
        val second = duration.toInt() / 2

        com.arthenica.ffmpegkit.FFmpegKit.execute(
            """-ss $second -i "${episodeFilename()}" -frames:v 1 -update true "$outFile" -y""",
        )

        if (tempFile.length() > 0L) {
            coverManager.update(anime, tempFile.inputStream())
        }
    }

       companion object {
        const val ID = 0L
        const val PELIS_ID = -1L
        const val SERIES_ID = -2L
        val ALL_IDS = setOf(ID, PELIS_ID, SERIES_ID)
        const val HELP_URL = "https://aniyomi.org/help/guides/local-anime/"

        private val SEASON_REGEX = Regex("""(?i)(?:^|\b|\s|\[|_)(?:s|season\s*)(\d+)(?:\s|e|x|\||-|\.|\b|\]|_|$)""")
        private const val DEFAULT_COVER_NAME = "cover.jpg"
        private const val DEFAULT_THUMBNAIL_NAME = "thumbnail.jpg"
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)
    }
}

actual fun Anime.isLocal(): Boolean = source in LocalAnimeSource.ALL_IDS

actual fun Source.isLocal(): Boolean = id in LocalAnimeSource.ALL_IDS

actual fun DomainSource.isLocal(): Boolean = id in LocalAnimeSource.ALL_IDS

