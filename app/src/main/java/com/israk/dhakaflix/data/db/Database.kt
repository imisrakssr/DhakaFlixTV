package com.israk.dhakaflix.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One movie or series folder on the server. */
@Entity(
    tableName = "items",
    indices = [Index("groupKey"), Index("uploadedAt"), Index("parentUrl"), Index("type"), Index("isPrimary"), Index("isPrimary", "type", "uploadedAt")],
)
data class ItemEntity(
    @PrimaryKey val id: String,          // absolute folder URL
    val type: String,                    // MediaType.name
    val sourceId: String,
    val parentUrl: String,
    val rawName: String,
    val title: String,
    val year: Int?,
    val quality: String?,
    val tags: String?,                   // comma separated
    val uploadedAt: Long,
    val firstSeenAt: Long,
    val groupKey: String,
    val isPrimary: Boolean = true,
    // --- metadata (TMDB) ---
    val metaState: Int = META_PENDING,
    val tmdbId: Int? = null,
    val tmdbTitle: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val voteCount: Int? = null,
    val genres: String? = null,          // "|Action|Drama|"
    val releaseYear: Int? = null,
    val runtime: Int? = null,
    val castJson: String? = null,
    val tagline: String? = null,
    val detailsFetched: Boolean = false,
    val localPoster: String? = null,     // server-provided jpg
    @ColumnInfo(defaultValue = "0") val posterChecked: Boolean = false,
    /** TMDB original language ("en", "hi", "ko" …): sorts series into categories by language, not by folder. */
    val lang: String? = null,
) {
    @get:Ignore val isSeries: Boolean get() = type == "SERIES"
    @get:Ignore val displayTitle: String get() = tmdbTitle ?: title
    @get:Ignore val displayYear: Int? get() = releaseYear ?: year
    @get:Ignore val genreList: List<String> get() = genres?.split('|')?.filter { it.isNotBlank() } ?: emptyList()

    companion object {
        const val META_PENDING = 0
        const val META_MATCHED = 1
        const val META_NOT_FOUND = 2
        const val META_MANUAL = 3
        const val META_HIDDEN = 4    // other-server index row: never looked up
        const val META_EMPTY = 5     // the folder has nothing to play: kept out of the library
    }
}

@Entity(tableName = "dir_cache")
data class DirCacheEntity(@PrimaryKey val url: String, val modified: Long)

@Entity(tableName = "user_state")
data class UserStateEntity(
    @PrimaryKey val itemId: String,
    val inWatchlist: Boolean = false,
    val watchlistAt: Long = 0,
    val watched: Boolean = false,
    val watchedAt: Long = 0,
)

@Entity(tableName = "progress", indices = [Index("itemId"), Index("updatedAt")])
data class ProgressEntity(
    @PrimaryKey val fileUrl: String,
    val itemId: String,
    val label: String?,      // e.g. "S01E02"
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean,
) {
    @get:Ignore val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Dao
abstract class ItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(items: List<ItemEntity>)

    @Query("SELECT id FROM items WHERE parentUrl = :parent")
    abstract suspend fun idsByParent(parent: String): List<String>

    @Query("UPDATE items SET uploadedAt = :uploadedAt, rawName = :rawName, quality = :quality, tags = :tags WHERE id = :id")
    abstract suspend fun updateListing(id: String, uploadedAt: Long, rawName: String, quality: String?, tags: String?)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM items WHERE sourceId NOT IN (:sourceIds)")
    abstract suspend fun deleteNotInSources(sourceIds: List<String>)

    @Transaction
    open suspend fun upsertListing(parent: String, items: List<ItemEntity>) {
        val existing = idsByParent(parent).toHashSet()
        val fresh = items.filter { it.id !in existing }
        // new rows stay hidden until they are matched to TMDB (recomputePrimary shows them)
        if (fresh.isNotEmpty()) insertAll(fresh.map { it.copy(isPrimary = false) })
        items.filter { it.id in existing }.forEach {
            updateListing(it.id, it.uploadedAt, it.rawName, it.quality, it.tags)
        }
        val keep = items.map { it.id }.toHashSet()
        val gone = existing.filter { it !in keep }
        gone.chunked(500).forEach { deleteIds(it) }
    }

    @Query("SELECT * FROM items WHERE id = :id")
    abstract fun observe(id: String): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE id = :id")
    abstract suspend fun get(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    abstract suspend fun getMany(ids: List<String>): List<ItemEntity>

    /** Followed series (watchlist or started) with uploads newer than the last time you watched / added them. */
    @Query(
        "SELECT * FROM items WHERE type = 'SERIES' AND isPrimary = 1 AND " +
            "(id IN (SELECT itemId FROM user_state WHERE inWatchlist = 1) OR id IN (SELECT itemId FROM progress)) AND " +
            "uploadedAt > COALESCE((SELECT MAX(p.updatedAt) FROM progress p WHERE p.itemId = items.id), " +
            "(SELECT u.watchlistAt FROM user_state u WHERE u.itemId = items.id), 0) " +
            "ORDER BY uploadedAt DESC LIMIT 30"
    )
    abstract fun newEpisodes(): Flow<List<ItemEntity>>

    @Query(
        "SELECT * FROM items WHERE type = :type AND (LOWER(title) = :title OR LOWER(tmdbTitle) = :title) " +
            "AND (year = :year OR releaseYear = :year) ORDER BY isPrimary DESC, uploadedAt DESC LIMIT 1"
    )
    abstract suspend fun byTitleYear(title: String, year: Int, type: String): ItemEntity?

    @Query("SELECT * FROM items WHERE tmdbId = :tmdbId AND type = :type ORDER BY isPrimary DESC, uploadedAt DESC LIMIT 1")
    abstract suspend fun byTmdb(tmdbId: Int, type: String): ItemEntity?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    abstract fun observeMany(ids: List<String>): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE isPrimary = 1 AND (:type IS NULL OR type = :type) ORDER BY uploadedAt DESC LIMIT :limit")
    abstract fun latest(type: String?, limit: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE isPrimary = 1 AND sourceId = :sourceId ORDER BY uploadedAt DESC LIMIT :limit")
    abstract fun latestInSource(sourceId: String, limit: Int): Flow<List<ItemEntity>>



    @Query("SELECT * FROM items WHERE isPrimary = 1 AND voteCount >= 300 ORDER BY rating DESC LIMIT :limit")
    abstract fun topRated(limit: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE isPrimary = 1 AND genres LIKE '%|' || :genre || '|%' ORDER BY uploadedAt DESC LIMIT :limit")
    abstract fun byGenre(genre: String, limit: Int): Flow<List<ItemEntity>>

    @RawQuery(observedEntities = [ItemEntity::class])
    abstract fun browse(query: SupportSQLiteQuery): PagingSource<Int, ItemEntity>

    @RawQuery(observedEntities = [ItemEntity::class])
    abstract fun rawFlow(query: SupportSQLiteQuery): Flow<List<ItemEntity>>

    @Query("UPDATE items SET lang = :lang WHERE id = :id")
    abstract suspend fun setLang(id: String, lang: String?)

    /** Matched series still missing their language (filled from TMDB details, a few at a time). */
    @Query("SELECT * FROM items WHERE lang IS NULL AND tmdbId IS NOT NULL AND metaState IN (1, 3) AND type = 'SERIES' LIMIT :limit")
    abstract suspend fun needingLang(limit: Int): List<ItemEntity>

    /** Same TMDB title elsewhere already has its language: copy it. */
    @Query("UPDATE items SET lang = (SELECT j.lang FROM items j WHERE j.tmdbId = items.tmdbId AND j.type = items.type AND j.lang IS NOT NULL LIMIT 1) WHERE lang IS NULL AND tmdbId IS NOT NULL")
    abstract suspend fun shareLang()

    @Query("SELECT * FROM items WHERE metaState = 0 ORDER BY uploadedAt DESC LIMIT :limit")
    abstract suspend fun pendingMeta(limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items")
    abstract fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE metaState = 0")
    abstract fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM items")
    abstract suspend fun countForAllSources(): Int

    @Query("SELECT COUNT(*) FROM items WHERE metaState = 0")
    abstract suspend fun pendingNow(): Int

    @Update
    abstract suspend fun update(item: ItemEntity)

    /**
     * One card per title, played from its best copy: 1080p first, then 720p, then the rest; DhakaFlix before
     * other servers, then newest. Only titles matched to TMDB with a poster are shown (on every server):
     * no poster or details means junk, an empty folder, or not looked up yet — those stay hidden.
     */
    @Query(
        "UPDATE items SET isPrimary = CASE WHEN id = (SELECT j.id FROM items j WHERE j.groupKey = items.groupKey " +
            "AND j.metaState IN (1, 3) AND j.tmdbId IS NOT NULL AND j.posterPath IS NOT NULL " +
            "ORDER BY CASE WHEN LOWER(j.quality) = '1080p' THEN 0 WHEN LOWER(j.quality) = '720p' THEN 1 ELSE 2 END, " +
            "(j.sourceId LIKE 'bk-%' OR j.sourceId LIKE 'rd-%'), j.uploadedAt DESC LIMIT 1) THEN 1 ELSE 0 END"
    )
    abstract suspend fun recomputePrimary()

    @Query("SELECT * FROM items WHERE groupKey = :groupKey ORDER BY uploadedAt DESC")
    abstract fun versions(groupKey: String): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM items WHERE sourceId LIKE 'bk-%' OR sourceId LIKE 'rd-%'")
    abstract fun otherServerCount(): Flow<Int>

    data class MatchedKey(val id: String, val type: String, val title: String, val tmdbTitle: String?, val year: Int?, val releaseYear: Int?)

    @Query("SELECT id, type, title, tmdbTitle, year, releaseYear FROM items WHERE tmdbId IS NOT NULL AND metaState IN (1, 3)")
    abstract suspend fun matchedKeys(): List<MatchedKey>

    /** Rows kept hidden by v2.0.1–2.0.4 (BokaSoka index) join the library: look them up like the rest. */
    @Query("UPDATE items SET metaState = 0 WHERE metaState = 4")
    abstract suspend fun unhideOldIndex()

    @Query("SELECT * FROM items WHERE groupKey = :groupKey ORDER BY uploadedAt DESC")
    abstract suspend fun versionsNow(groupKey: String): List<ItemEntity>

    /** Same title (any of [titles], lower case) on another server, year within one either way. */
    @Query(
        "SELECT * FROM items WHERE sourceId LIKE 'bk-%' AND type = :type AND LOWER(title) IN (:titles) " +
            "AND (:year IS NULL OR year IS NULL OR year BETWEEN :year - 1 AND :year + 1) LIMIT 8"
    )
    abstract suspend fun otherServerMatches(type: String, titles: List<String>, year: Int?): List<ItemEntity>

    /** Years that really have titles (shown cards only, sane range). */
    @Query("SELECT DISTINCT year FROM items WHERE isPrimary = 1 AND year BETWEEN 1900 AND 2100 ORDER BY year DESC")
    abstract fun years(): Flow<List<Int>>

    @Query("SELECT DISTINCT year FROM items WHERE isPrimary = 1 AND type = :type AND year BETWEEN 1900 AND 2100 ORDER BY year DESC")
    abstract fun yearsFor(type: String): Flow<List<Int>>

    @Query("UPDATE items SET metaState = 0 WHERE metaState = 2")
    abstract suspend fun retryNotFound()

    @Query("UPDATE items SET metaState = 5, isPrimary = 0 WHERE id = :id")
    abstract suspend fun markEmpty(id: String)

    /** Full rescan: give folders marked empty another look (episodes may have arrived). */
    @Query("UPDATE items SET metaState = 0 WHERE metaState = 5")
    abstract suspend fun resetEmpty()

    /** RongDhong's stand-in poster (a plain blue box) is never used. */
    @Query("UPDATE items SET localPoster = NULL WHERE localPoster LIKE 'http://172.31.255.238/%'")
    abstract suspend fun dropRongDhongStandInPosters()

    @Query("UPDATE items SET localPoster = :url WHERE id = :id")
    abstract suspend fun setLocalPoster(id: String, url: String)

    @Query("SELECT * FROM items WHERE posterPath IS NULL AND localPoster IS NULL AND posterChecked = 0 AND (:anyMeta = 1 OR metaState != 0) ORDER BY uploadedAt DESC LIMIT :limit")
    abstract suspend fun needingPoster(anyMeta: Boolean, limit: Int): List<ItemEntity>

    @Query("UPDATE items SET posterChecked = 1, localPoster = COALESCE(:poster, localPoster) WHERE id = :id")
    abstract suspend fun setPosterChecked(id: String, poster: String?)

    @Query("SELECT * FROM items WHERE isPrimary = 1 AND (backdropPath IS NOT NULL OR localPoster IS NOT NULL) ORDER BY uploadedAt DESC LIMIT :limit")
    abstract fun featured(limit: Int): Flow<List<ItemEntity>>

    /** Screensaver slides: any title matched to TMDB with a picture, one per title, picked at random. */
    @Query(
        "SELECT * FROM items WHERE metaState IN (1, 3) AND (backdropPath IS NOT NULL OR posterPath IS NOT NULL) " +
            "GROUP BY groupKey ORDER BY (backdropPath IS NULL), RANDOM() LIMIT :limit"
    )
    abstract suspend fun saverSlides(limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items WHERE sourceId = :sourceId")
    abstract suspend fun countForSource(sourceId: String): Int

    @Query("UPDATE items SET metaState = :state WHERE id = :id")
    abstract suspend fun setMetaState(id: String, state: Int)

    @Query(
        """UPDATE items SET metaState = :state, tmdbId = :tmdbId, tmdbTitle = :tmdbTitle, posterPath = :posterPath,
        backdropPath = :backdropPath, overview = :overview, rating = :rating, voteCount = :voteCount, genres = :genres,
        releaseYear = :releaseYear, groupKey = :groupKey, detailsFetched = 0 WHERE id = :id"""
    )
    abstract suspend fun applyMeta(
        id: String, state: Int, tmdbId: Int, tmdbTitle: String?, posterPath: String?, backdropPath: String?,
        overview: String?, rating: Double?, voteCount: Int?, genres: String?, releaseYear: Int?, groupKey: String,
    )

    @Query(
        """UPDATE items SET runtime = :runtime, castJson = :castJson, tagline = :tagline,
        genres = COALESCE(:genres, genres), overview = COALESCE(:overview, overview),
        posterPath = COALESCE(posterPath, :posterPath), backdropPath = COALESCE(backdropPath, :backdropPath),
        detailsFetched = 1 WHERE id = :id"""
    )
    abstract suspend fun applyDetails(
        id: String, runtime: Int?, castJson: String?, tagline: String?, genres: String?, overview: String?,
        posterPath: String?, backdropPath: String?,
    )
}

@Dao
interface DirCacheDao {
    @Query("SELECT * FROM dir_cache")
    suspend fun all(): List<DirCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: DirCacheEntity)

    @Query("DELETE FROM dir_cache")
    suspend fun clear()
}

@Dao
interface UserDao {
    @Query("SELECT * FROM user_state WHERE itemId = :id")
    fun observe(id: String): Flow<UserStateEntity?>

    @Query("SELECT * FROM user_state WHERE itemId = :id")
    suspend fun get(id: String): UserStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: UserStateEntity)

    @Query("SELECT items.* FROM items INNER JOIN user_state ON items.id = user_state.itemId WHERE user_state.inWatchlist = 1 ORDER BY user_state.watchlistAt DESC")
    fun watchlist(): Flow<List<ItemEntity>>

    @Query("SELECT items.* FROM items INNER JOIN user_state ON items.id = user_state.itemId WHERE user_state.watched = 1 ORDER BY user_state.watchedAt DESC LIMIT 100")
    fun watched(): Flow<List<ItemEntity>>

    @Query("SELECT itemId FROM user_state WHERE watched = 1")
    fun watchedIds(): Flow<List<String>>

    @Query("SELECT * FROM user_state")
    suspend fun all(): List<UserStateEntity>

    @Query("SELECT COUNT(*) FROM user_state WHERE inWatchlist = 1")
    suspend fun watchlistCount(): Int
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ProgressEntity)

    @Query("SELECT * FROM progress WHERE fileUrl = :url")
    suspend fun get(url: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE itemId = :itemId ORDER BY updatedAt DESC")
    fun forItem(itemId: String): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE completed = 0 AND positionMs > 20000 ORDER BY updatedAt DESC LIMIT 80")
    fun inProgress(): Flow<List<ProgressEntity>>

    @Query("UPDATE progress SET completed = 1 WHERE itemId = :itemId")
    suspend fun completeItem(itemId: String)

    @Query("DELETE FROM progress WHERE itemId = :itemId")
    suspend fun clearItem(itemId: String)

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE itemId = :itemId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestFor(itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE itemId = :itemId AND season = :season AND episode = :episode LIMIT 1")
    suspend fun episode(itemId: String, season: Int, episode: Int): ProgressEntity?
}

@Database(
    entities = [ItemEntity::class, DirCacheEntity::class, UserStateEntity::class, ProgressEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun dirs(): DirCacheDao
    abstract fun user(): UserDao
    abstract fun progress(): ProgressDao

    companion object {
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "dhakaflix.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lang TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_items_isPrimary_type_uploadedAt ON items (isPrimary, type, uploadedAt)")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN posterChecked INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
