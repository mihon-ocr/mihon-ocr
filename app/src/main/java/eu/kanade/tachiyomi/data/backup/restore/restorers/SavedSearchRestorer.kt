package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SavedSearchRestorer(
    private val database: Database = Injekt.get(),
) {
    suspend fun restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) {
        if (backupSavedSearches.isEmpty()) return

        val currentSavedSearches = database.saved_searchQueries.selectAll().awaitAsList()

        database.transaction {
            backupSavedSearches.filter { backupSavedSearch ->
                currentSavedSearches.none { currentSavedSearch ->
                    currentSavedSearch.source == backupSavedSearch.source &&
                        currentSavedSearch.name == backupSavedSearch.name &&
                        currentSavedSearch.query.orEmpty() == backupSavedSearch.query &&
                        (currentSavedSearch.filters_json ?: "[]") == backupSavedSearch.filterList
                }
            }.forEach { backupSavedSearch ->
                database.saved_searchQueries.insert(
                    source = backupSavedSearch.source,
                    name = backupSavedSearch.name,
                    query = backupSavedSearch.query.takeUnless(String::isBlank),
                    filtersJson = backupSavedSearch.filterList
                        .takeUnless(String::isBlank)
                        ?.takeUnless { it == "[]" },
                )
            }
        }
    }
}
