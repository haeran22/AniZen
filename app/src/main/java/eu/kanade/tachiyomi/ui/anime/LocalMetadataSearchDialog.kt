package eu.kanade.tachiyomi.ui.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.anime.model.Anime

private val CLEAN_FOLDER_REGEX = Regex("(?i)\\[.*?\\]|\\(.*?\\)|(?:s|season|temporada)\\s*\\d+")
private val DETECT_SEASON_REGEX = Regex("(?i)(?:^|\\b|\\s|\\[|_)(?:s|season\\s*|temporada\\s*)(\\d+)(?:\\s|e|x|\\||-|\\.|\\b|\\]|_|$)")

@Composable
fun LocalMetadataSearchDialog(
    anime: Anime,
    onDismissRequest: () -> Unit,
    onApplyMetadata: (
        title: String,
        author: String,
        artist: String,
        description: String,
        genres: List<String>,
        status: Int,
        coverSaved: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val detectedSeason = remember(anime.url) {
        val folderPart = if (anime.url.contains('/')) anime.url.substringAfterLast('/') else anime.url
        DETECT_SEASON_REGEX.find(folderPart)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    val initialQuery = remember(anime.url, anime.title) {
        val rawName = if (anime.url.contains('/')) {
            anime.url.substringBefore('/')
        } else {
            anime.title.ifBlank { anime.url }
        }
        rawName.replace(CLEAN_FOLDER_REGEX, " ").replace(Regex("\\s+"), " ").trim()
    }

    var searchQuery by remember { mutableStateOf(initialQuery) }
    var mediaType by remember { mutableStateOf("tv") }
    var language by remember { mutableStateOf("es-MX") }
    var titleFormat by remember { mutableStateOf("romaji") }
    var coverSource by remember { mutableStateOf("bangumi") }

    var isSearching by remember { mutableStateOf(false) }
    var isLoadingDetails by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var searchResults by remember { mutableStateOf<List<LocalMetadataFetcher.TmdbSearchItem>>(emptyList()) }
    var selectedItem by remember { mutableStateOf<LocalMetadataFetcher.TmdbSearchItem?>(null) }
    var resolvedMetadata by remember { mutableStateOf<LocalMetadataFetcher.ResolvedMetadata?>(null) }
    var selectedSeason by remember { mutableIntStateOf(detectedSeason ?: 1) }
    var manualBgmQuery by remember { mutableStateOf("") }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        scope.launch {
            isSearching = true
            selectedItem = null
            resolvedMetadata = null
            searchResults = LocalMetadataFetcher.searchTmdb(q, mediaType, language)
            isSearching = false
        }
    }

    fun loadDetailsFor(item: LocalMetadataFetcher.TmdbSearchItem, season: Int?, bgmOverride: String? = null) {
        scope.launch {
            isLoadingDetails = true
            selectedItem = item
            val meta = LocalMetadataFetcher.fetchFullMetadata(
                tmdbId = item.id,
                mediaType = item.mediaType,
                language = language,
                seasonNumber = season,
                manualBangumiQuery = bgmOverride,
            )
            resolvedMetadata = meta
            if (meta != null) {
                selectedSeason = meta.selectedSeason
                if (bgmOverride == null) {
                    manualBgmQuery = meta.titleNative.ifBlank { meta.titleRomaji }
                }
                if (meta.bangumiCoverUrl.isNullOrBlank() && !meta.tmdbCoverUrl.isNullOrBlank()) {
                    coverSource = "tmdb"
                } else if (!meta.bangumiCoverUrl.isNullOrBlank()) {
                    coverSource = "bangumi"
                }
            }
            isLoadingDetails = false
        }
    }

    LaunchedEffect(Unit) {
        if (searchQuery.isNotBlank()) {
            runSearch()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        title = {
            Text(
                text = "Autocompletar (TMDB + Bangumi)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Buscar título") },
                    )
                    Button(
                        onClick = { runSearch() },
                        enabled = !isSearching && !isLoadingDetails && !isSaving,
                    ) {
                        Text("Buscar")
                    }
                }

                // Tipo e Idioma
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = mediaType == "tv",
                        onClick = { mediaType = "tv"; runSearch() },
                        label = { Text("Serie (TV)") },
                    )
                    FilterChip(
                        selected = mediaType == "movie",
                        onClick = { mediaType = "movie"; runSearch() },
                        label = { Text("Película") },
                    )
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = language == "es-MX",
                        onClick = {
                            language = "es-MX"
                            selectedItem?.let { loadDetailsFor(it, selectedSeason) }
                        },
                        label = { Text("es-MX") },
                    )
                    FilterChip(
                        selected = language == "es-ES",
                        onClick = {
                            language = "es-ES"
                            selectedItem?.let { loadDetailsFor(it, selectedSeason) }
                        },
                        label = { Text("es-ES") },
                    )
                }

                // Formato de título
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = titleFormat == "romaji",
                        onClick = { titleFormat = "romaji" },
                        label = { Text("Romaji") },
                    )
                    FilterChip(
                        selected = titleFormat == "es",
                        onClick = { titleFormat = "es" },
                        label = { Text("Español") },
                    )
                    FilterChip(
                        selected = titleFormat == "native",
                        onClick = { titleFormat = "native" },
                        label = { Text("Nativo") },
                    )
                }

                if (isSearching || isLoadingDetails) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (resolvedMetadata != null && selectedItem != null) {
                    val meta = resolvedMetadata!!
                    val chosenTitle = when (titleFormat) {
                        "native" -> meta.titleNative.ifBlank { meta.titleRomaji.ifBlank { meta.titleEs } }
                        "es" -> meta.titleEs.ifBlank { meta.titleRomaji.ifBlank { meta.titleNative } }
                        else -> meta.titleRomaji.ifBlank { meta.titleEs.ifBlank { meta.titleNative } }
                    }
                    val activeCoverUrl = if (coverSource == "bangumi" && !meta.bangumiCoverUrl.isNullOrBlank()) {
                        meta.bangumiCoverUrl
                    } else {
                        meta.tmdbCoverUrl
                    }

                    OutlinedButton(
                        onClick = {
                            resolvedMetadata = null
                            selectedItem = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("← Volver a resultados")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AsyncImage(
                            model = activeCoverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(84.dp)
                                .height(124.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chosenTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Estudio: ${meta.studio.ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = if (meta.bangumiId != null) "Bangumi ID: #${meta.bangumiId}" else "Bangumi: sin coincidencia",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (meta.episodes.isNotEmpty()) {
                                Text(
                                    text = "Episodios listos: ${meta.episodes.size} (sin descripción)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    // Origen de carátula
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = coverSource == "bangumi",
                            onClick = { coverSource = "bangumi" },
                            enabled = !meta.bangumiCoverUrl.isNullOrBlank(),
                            label = { Text("Cover: Bangumi") },
                        )
                        FilterChip(
                            selected = coverSource == "tmdb",
                            onClick = { coverSource = "tmdb" },
                            enabled = !meta.tmdbCoverUrl.isNullOrBlank(),
                            label = { Text("Cover: TMDB") },
                        )
                    }

                    // Selector de temporada
                    if (meta.seasons.isNotEmpty()) {
                        Text(
                            text = "Temporada:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            meta.seasons.take(6).forEach { s ->
                                FilterChip(
                                    selected = selectedSeason == s.seasonNumber,
                                    onClick = {
                                        selectedSeason = s.seasonNumber
                                        loadDetailsFor(selectedItem!!, s.seasonNumber)
                                    },
                                    label = { Text("T${s.seasonNumber} (${s.episodeCount})") },
                                )
                            }
                        }
                    }

                    // Búsqueda manual de portada en Bangumi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = manualBgmQuery,
                            onValueChange = { manualBgmQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Bangumi (JP o ID)") },
                        )
                        OutlinedButton(
                            onClick = {
                                val q = manualBgmQuery.trim()
                                if (q.isNotEmpty()) {
                                    loadDetailsFor(selectedItem!!, selectedSeason, bgmOverride = q)
                                }
                            },
                        ) {
                            Text("Buscar")
                        }
                    }
                } else {
                    if (searchResults.isEmpty()) {
                        Text(
                            text = "Sin resultados. Prueba con otro nombre o cambia entre Serie / Película.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(searchResults) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { loadDetailsFor(item, detectedSeason) },
                                ) {
                                    Column {
                                        AsyncImage(
                                            model = item.posterUrl,
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(2f / 3f),
                                        )
                                        Column(modifier = Modifier.padding(4.dp)) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = item.year,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (resolvedMetadata != null) {
                Button(
                    enabled = !isSaving,
                    onClick = {
                        val meta = resolvedMetadata ?: return@Button
                        val chosenTitle = when (titleFormat) {
                            "native" -> meta.titleNative.ifBlank { meta.titleRomaji.ifBlank { meta.titleEs } }
                            "es" -> meta.titleEs.ifBlank { meta.titleRomaji.ifBlank { meta.titleNative } }
                            else -> meta.titleRomaji.ifBlank { meta.titleEs.ifBlank { meta.titleNative } }
                        }
                        val activeCoverUrl = if (coverSource == "bangumi" && !meta.bangumiCoverUrl.isNullOrBlank()) {
                            meta.bangumiCoverUrl
                        } else {
                            meta.tmdbCoverUrl
                        }

                        scope.launch {
                            isSaving = true
                            val result = LocalMetadataFetcher.saveFilesToAnimeDir(
                                anime = anime,
                                episodes = meta.episodes,
                                coverUrl = activeCoverUrl,
                            )
                            isSaving = false
                            result.onSuccess { msg ->
                                context.toast(msg)
                                onApplyMetadata(
                                    chosenTitle,
                                    meta.studio,
                                    meta.studio,
                                    meta.description,
                                    meta.genres,
                                    meta.status,
                                    !activeCoverUrl.isNullOrBlank(),
                                )
                                onDismissRequest()
                            }.onFailure { err ->
                                context.toast("Error guardando archivos: ${err.message}")
                            }
                        }
                    },
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Aplicar y Guardar")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isSaving,
            ) {
                Text("Cancelar")
            }
        },
    )
}
