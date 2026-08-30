package app.movia.android.data.catalog

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CatalogInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            val appContext = ctx.applicationContext
            // Reclaim storage: delete obsolete 376MB embedded catalog.db if present from older versions
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val oldDb = appContext.getDatabasePath("catalog.db")
                    if (oldDb.exists()) {
                        oldDb.delete()
                        java.io.File("${oldDb.path}-wal").delete()
                        java.io.File("${oldDb.path}-shm").delete()
                        java.io.File("${oldDb.path}-journal").delete()
                    }
                } catch (_: Exception) {}
            }
            DemoCatalogRepository.init(appContext)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
