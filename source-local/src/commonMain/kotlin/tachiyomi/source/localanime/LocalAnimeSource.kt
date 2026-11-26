package tachiyomi.source.localanime

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.model.Source as DomainSource

expect class LocalAnimeSource : CatalogueSource

expect fun Anime.isLocal(): Boolean

expect fun Source.isLocal(): Boolean

expect fun DomainSource.isLocal(): Boolean
