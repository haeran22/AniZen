package tachiyomi.source.localanime.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager

actual class LocalAnimeSourceFileSystem(
    private val storageManager: StorageManager,
) {

    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    actual fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    actual fun getAnimeDirectory(name: String): UniFile? {
        var current: UniFile? = getBaseDirectory()
        for (segment in name.split('/').filter { it.isNotEmpty() }) {
            current = current?.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    actual fun getFilesInAnimeDirectory(name: String): List<UniFile> {
        return getAnimeDirectory(name)?.listFiles().orEmpty().toList()
    }
}
