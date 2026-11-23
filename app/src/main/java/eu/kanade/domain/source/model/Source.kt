package eu.kanade.domain.source.model

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionManager
import exh.source.MERGED_SOURCE_ID
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANZ -->
private val NULL_ICON_SENTINEL = Any()
private val iconBitmapCache = java.util.concurrent.ConcurrentHashMap<Long, Any>() // ANZ

val Source.icon: ImageBitmap?
    get() {
        val cached = iconBitmapCache.getOrPut(id) {
                        val drawable = if (id == MERGED_SOURCE_ID) {
                ContextCompat.getDrawable(Injekt.get<Context>(), R.drawable.ic_splash_logo_raw)
            } else {
                Injekt.get<ExtensionManager>().getAppIconForSource(id)
            }
            drawable
                ?.toBitmap()
                ?.asImageBitmap()
                ?: NULL_ICON_SENTINEL
        }
        return if (cached === NULL_ICON_SENTINEL) null else cached as ImageBitmap
    }
// ANZ <--
