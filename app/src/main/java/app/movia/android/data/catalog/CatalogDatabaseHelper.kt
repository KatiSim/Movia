package app.movia.android.data.catalog

import android.content.Context

/**
 * Legacy CatalogDatabaseHelper maintained for backward compatibility.
 * All catalog queries are now processed by DemoCatalogRepository via the HTTP REST API.
 */
class CatalogDatabaseHelper private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: CatalogDatabaseHelper? = null

        fun getInstance(context: Context): CatalogDatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CatalogDatabaseHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
