package exh.source

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.GetMergedAnimeById
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toSAnime
import tachiyomi.domain.anime.repository.AnimeMergeRepository
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.injectLazy

class MergedAnimeSource : AnimeSource {

    override val id: Long = MERGED_SOURCE_ID
    override val name: String = "Merged"
    override val lang: String = "all"
    override fun toString(): String = name

    private val getAnime: GetAnime by injectLazy()
    private val getMergedAnimeById: GetMergedAnimeById by injectLazy()
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId by injectLazy()
    private val animeMergeRepository: AnimeMergeRepository by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    @Deprecated("Use the combined suspend API instead")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val mergedAnime = getAnime.await(anime.url, MERGED_SOURCE_ID) ?: return anime
        val references = animeMergeRepository.getReferencesById(mergedAnime.id)
        val infoReference = references.firstOrNull { it.isInfoAnime }
            ?: references.firstOrNull { it.animeSourceId != MERGED_SOURCE_ID }
            ?: return anime
        val infoAnime = infoReference.animeId?.let { getAnime.await(it) } ?: return anime
        return infoAnime.toSAnime()
    }

    @Deprecated("Use the combined suspend API instead")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return emptyList()
    }

    override suspend fun getHosterList(anime: SAnime, episode: SEpisode): List<Hoster> {
        val realSource = resolveRealSource(episode) ?: throw Exception("Source not found")
        return realSource.getHosterList(anime, episode)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val realSource = resolveRealSource(episode) ?: throw Exception("Source not found")
        return realSource.getVideoList(episode)
    }

    private suspend fun resolveRealSource(episode: SEpisode): AnimeSource? {
        val allMerged = animeMergeRepository.getMergedAnime()
        for (merged in allMerged) {
            val animes = getMergedAnimeById.await(merged.id)
            for (child in animes) {
                val episodes = getEpisodesByAnimeId.await(child.id)
                if (episodes.any { it.url == episode.url }) {
                    return sourceManager.get(child.source)
                }
            }
        }
        return null
    }
}
