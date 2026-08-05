package com.isene.watchit.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isene.watchit.data.Net
import com.isene.watchit.data.RatingsRepo
import com.isene.watchit.data.Repo
import com.isene.watchit.data.ShareRepo
import com.isene.watchit.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.fe2o3_mobile_core.Catalog
import uniffi.fe2o3_mobile_core.Details
import uniffi.fe2o3_mobile_core.ListItem
import uniffi.fe2o3_mobile_core.Rating
import uniffi.fe2o3_mobile_core.chartKind
import uniffi.fe2o3_mobile_core.filterSort
import uniffi.fe2o3_mobile_core.genresOf
import uniffi.fe2o3_mobile_core.mergeCatalogs
import uniffi.fe2o3_mobile_core.mergeItems
import uniffi.fe2o3_mobile_core.mergeRatings
import uniffi.fe2o3_mobile_core.parseChart
import uniffi.fe2o3_mobile_core.parseDetails
import uniffi.fe2o3_mobile_core.parseSearch
import uniffi.fe2o3_mobile_core.sortByMine
import uniffi.fe2o3_mobile_core.ratingFor
import uniffi.fe2o3_mobile_core.ratingKey
import uniffi.fe2o3_mobile_core.tmdbChartUrl
import uniffi.fe2o3_mobile_core.tmdbDetailsUrl
import uniffi.fe2o3_mobile_core.tmdbSearchUrl

data class UiState(
    val view: String = "movies", // "movies" | "series"
    val filtered: List<ListItem> = emptyList(),
    val genres: List<String> = emptyList(),
    val genresInclude: List<String> = emptyList(),
    val genresExclude: List<String> = emptyList(),
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val ratingMin: Double = 0.0,
    val yearMin: Int = 0,
    val yearMax: Int = 0,
    val sort: String = "rating",
    val busy: Boolean = false,
    val status: String? = null,
)

data class SearchState(
    val query: String = "",
    val results: List<ListItem> = emptyList(),
    val busy: Boolean = false,
)

class WatchitViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    private var movies: List<ListItem> = emptyList()
    private var series: List<ListItem> = emptyList()
    private val details = HashMap<String, Details>()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _details = MutableStateFlow<Map<String, Details>>(emptyMap())
    val detailsFlow: StateFlow<Map<String, Details>> = _details.asStateFlow()
    /** Titles TMDB would not answer for. Without this the detail screen
     *  sits on "Loading details…" for ever — and some titles can never
     *  load: the desktop shares rows whose IMDB id TMDB has no mapping
     *  for (arcs and seasons like "Bleach: Sennen Kessen-hen"). */
    private val _unfetchable = MutableStateFlow<Set<String>>(emptySet())
    val unfetchable: StateFlow<Set<String>> = _unfetchable.asStateFlow()
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    /** My 1-10 scores, merged across every device writing into the shared
     *  folder. Empty until a folder is picked in Settings. */
    private val _ratings = MutableStateFlow<List<Rating>>(emptyList())
    val ratings: StateFlow<List<Rating>> = _ratings.asStateFlow()

    init {
        movies = Repo.loadItems(app, "movies.json")
        series = Repo.loadItems(app, "series.json")
        Repo.loadDetails(app).forEach { details[it.id] = it }
        _details.value = HashMap(details)
        reloadRatings()
        recompute()
    }

    /** Re-read the shared folder. Called on start and on every resume —
     *  the desktop may have rated something while the app was backgrounded,
     *  and Syncthing drops the file in without telling anyone.
     *
     *  Also picks up what else the folder carries: titles the desktop
     *  added, and the TMDB key, so it never has to be typed in here. */
    fun reloadRatings() {
        val uri = settings.syncTreeUri
        if (uri.isEmpty()) { _ratings.value = emptyList(); return }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val loaded = withContext(Dispatchers.IO) { RatingsRepo.loadAll(ctx, uri) }
            _ratings.value = loaded
            // The list is ordered by these when sort is "mine", so it has
            // to be rebuilt now that they have landed.
            if (settings.sort == "mine") recompute()
            // A key of our own always wins; this only fills an empty one.
            if (settings.tmdbKey.isEmpty()) {
                withContext(Dispatchers.IO) { ShareRepo.publishedKey(ctx, uri) }
                    ?.let { settings.tmdbKey = it }
            }
            val merged = withContext(Dispatchers.IO) {
                val theirs = ShareRepo.loadOthers(ctx, uri)
                if (theirs.movies.isEmpty() && theirs.series.isEmpty()) null
                else mergeCatalogs(Catalog(movies, series), theirs)
            }
            if (merged != null && (merged.movies.size != movies.size || merged.series.size != series.size)) {
                val gained = merged.movies.size - movies.size + merged.series.size - series.size
                movies = merged.movies
                series = merged.series
                withContext(Dispatchers.IO) {
                    Repo.saveItems(ctx, "movies.json", movies)
                    Repo.saveItems(ctx, "series.json", series)
                }
                recompute(status = "$gained title(s) from the desktop")
            }
            // Publish ours back, so the desktop sees what was added here.
            withContext(Dispatchers.IO) {
                ShareRepo.saveMine(ctx, uri, Catalog(movies, series))
            }
        }
    }

    /** My score for a title: by key, falling back to title+year (the
     *  desktop catalog may key the same film by an IMDB tconst). The key
     *  is not the bare id — movie 745 and tv 745 are different titles. */
    fun myRating(item: ListItem): Int =
        ratingFor(_ratings.value, ratingKey(item.id, item.kind), item.title, item.year)

    /** Score a title 1-10, or clear it with 0. The clear is stored as a
     *  timestamped 0, not a deletion, so it survives the next merge. */
    fun rate(item: ListItem, score: Int) {
        val entry = Rating(
            id = ratingKey(item.id, item.kind),
            score = score.coerceIn(0, 10),
            ts = System.currentTimeMillis() / 1000,
            title = item.title,
            year = item.year,
        )
        // Merged, not appended: the entry may fold into one the desktop
        // wrote under a different id for the same film.
        val merged = mergeRatings(listOf(_ratings.value, listOf(entry)))
        _ratings.value = merged
        if (settings.sort == "mine") recompute()
        val uri = settings.syncTreeUri
        if (uri.isEmpty()) {
            recompute(status = "Pick a sync folder in Settings to keep ratings")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RatingsRepo.saveMine(getApplication(), uri, merged) }
        }
    }

    fun settingsObj() = settings

    private fun catalog(view: String = settings.view) = if (view == "movies") movies else series

    private fun recompute(status: String? = _ui.value.status, busy: Boolean = _ui.value.busy) {
        val view = settings.view
        val cat = catalog(view)
        val dumpIds = if (view == "movies") settings.dumpMovies else settings.dumpSeries
        var filtered = filterSort(
            cat, settings.ratingMin, settings.yearMin, settings.yearMax,
            settings.genresInclude, settings.genresExclude, dumpIds, settings.sort,
        )
        // "Mine" orders by my own score, so it needs the ratings — which
        // live in the shared folder, not in the catalog filterSort sees.
        if (settings.sort == "mine") filtered = sortByMine(filtered, _ratings.value)
        _ui.value = UiState(
            view = view,
            filtered = filtered,
            genres = genresOf(cat),
            genresInclude = settings.genresInclude,
            genresExclude = settings.genresExclude,
            movieCount = movies.size,
            seriesCount = series.size,
            ratingMin = settings.ratingMin,
            yearMin = settings.yearMin,
            yearMax = settings.yearMax,
            sort = settings.sort,
            busy = busy,
            status = status,
        )
    }

    private fun status(msg: String?, busy: Boolean = false) = recompute(status = msg, busy = busy)

    // ---- view / filters ----
    fun toggleView() { settings.view = if (settings.view == "movies") "series" else "movies"; recompute() }
    /** Cycles the same three the TUI does: TMDB rating, A-Z, my rating. */
    fun toggleSort() {
        settings.sort = when (settings.sort) {
            "rating" -> "alpha"
            "alpha" -> "mine"
            else -> "rating"
        }
        recompute()
    }

    fun setSort(v: String) { settings.sort = v; recompute() }
    fun setRatingMin(v: Double) { settings.ratingMin = v.coerceIn(0.0, 10.0); recompute() }
    fun setYearMin(v: Int) { settings.yearMin = v; recompute() }
    fun setYearMax(v: Int) { settings.yearMax = v; recompute() }

    fun includeGenre(g: String) {
        settings.genresExclude = settings.genresExclude - g
        settings.genresInclude = if (g in settings.genresInclude) settings.genresInclude - g else settings.genresInclude + g
        recompute()
    }
    fun excludeGenre(g: String) {
        settings.genresInclude = settings.genresInclude - g
        settings.genresExclude = if (g in settings.genresExclude) settings.genresExclude - g else settings.genresExclude + g
        recompute()
    }
    fun clearGenre(g: String) {
        settings.genresInclude = settings.genresInclude - g
        settings.genresExclude = settings.genresExclude - g
        recompute()
    }
    fun clearAllGenres() {
        settings.genresInclude = emptyList(); settings.genresExclude = emptyList(); recompute()
    }

    // ---- wish / dump ----
    fun isWished(id: String): Boolean =
        id in (if (settings.view == "movies") settings.wishMovies else settings.wishSeries)
    fun isDumped(id: String): Boolean =
        id in (if (settings.view == "movies") settings.dumpMovies else settings.dumpSeries)

    fun toggleWish(id: String) {
        if (settings.view == "movies") settings.wishMovies = toggle(settings.wishMovies, id)
        else settings.wishSeries = toggle(settings.wishSeries, id)
        recompute()
    }
    fun toggleDump(id: String) {
        if (settings.view == "movies") settings.dumpMovies = toggle(settings.dumpMovies, id)
        else settings.dumpSeries = toggle(settings.dumpSeries, id)
        recompute()
    }
    private fun toggle(list: List<String>, id: String) = if (id in list) list - id else list + id

    fun wishItems(): List<ListItem> =
        itemsFor(if (settings.view == "movies") settings.wishMovies else settings.wishSeries)
    fun dumpItems(): List<ListItem> =
        itemsFor(if (settings.view == "movies") settings.dumpMovies else settings.dumpSeries)

    private fun itemsFor(ids: List<String>): List<ListItem> = ids.mapNotNull { id ->
        catalog().firstOrNull { it.id == id }
            ?: details[id]?.let { d ->
                ListItem(d.id, d.title, d.rating, d.year, d.genres, if (d.kind == "TVSeries") "tv" else "movie", d.posterUrl)
            }
    }

    fun detailsFor(id: String): Details? = details[id]
    fun titleFor(id: String): String =
        catalog().firstOrNull { it.id == id }?.title ?: details[id]?.title ?: id

    // ---- fetching ----
    fun fetchTopRated() = fetchCharts("top_rated_movies", "top_rated_tv", "top-rated")
    fun fetchPopular() = fetchCharts("popular_movies", "popular_tv", "popular")

    private fun fetchCharts(movieChart: String, tvChart: String, label: String) {
        val key = settings.tmdbKey
        if (key.isEmpty()) { status("Set your TMDB API key in Settings first"); return }
        if (_ui.value.busy) return
        status("Fetching $label lists…", busy = true)
        val movieLimit = settings.movieLimit
        val seriesLimit = settings.seriesLimit
        viewModelScope.launch(Dispatchers.IO) {
            val newMovies = fetchChart(movieChart, movieLimit, key)
            val newSeries = fetchChart(tvChart, seriesLimit, key)
            val mergedM = mergeItems(movies, newMovies)
            val mergedS = mergeItems(series, newSeries)
            withContext(Dispatchers.Main) {
                movies = mergedM; series = mergedS
                Repo.saveItems(getApplication(), "movies.json", movies)
                Repo.saveItems(getApplication(), "series.json", series)
                status("Loaded ${movies.size} movies, ${series.size} series", busy = false)
            }
        }
    }

    private fun fetchChart(chart: String, limit: Int, key: String): List<ListItem> {
        val kind = chartKind(chart) ?: return emptyList()
        val out = ArrayList<ListItem>()
        val pages = ((limit - 1) / 20) + 1
        for (page in 1..pages) {
            val url = tmdbChartUrl(chart, page.toUInt(), key) ?: break
            val body = Net.get(url) ?: break
            val items = parseChart(body, kind)
            if (items.isEmpty()) break
            out.addAll(items)
            if (out.size >= limit) break
        }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** Fetch details for up to [max] items in the current view that are missing
     *  or errored, folding year+genres back into the catalog rows. */
    fun fetchMissingDetails(max: Int = 15) {
        val key = settings.tmdbKey
        if (key.isEmpty()) { status("Set your TMDB API key in Settings first"); return }
        if (_ui.value.busy) return
        val missing = _ui.value.filtered.map { it.id }
            .filter { details[it]?.let { d -> !d.error && d.title.isNotEmpty() } != true }
            .take(max)
        if (missing.isEmpty()) { status("All details present"); return }
        status("Fetching ${missing.size} details…", busy = true)
        val region = settings.region
        val view = settings.view
        viewModelScope.launch(Dispatchers.IO) {
            val fetched = missing.mapNotNull { id ->
                val kind = catalog(view).firstOrNull { it.id == id }?.kind ?: "movie"
                Net.get(tmdbDetailsUrl(id, kind, key))?.let { parseDetails(it, id, kind, region) }
            }
            withContext(Dispatchers.Main) {
                fetched.forEach { storeDetails(it) }
                persistAfterDetails()
                status("Details updated", busy = false)
            }
        }
    }

    /** Fetch (or refresh) one title's details — used when opening detail view. */
    fun fetchDetails(id: String, kind: String) {
        val key = settings.tmdbKey
        if (key.isEmpty()) return
        // An IMDB id is not something TMDB answers to. Those rows come
        // from a desktop catalog whose migration could not resolve them;
        // asking anyway just spins.
        if (id.startsWith("tt")) {
            _unfetchable.value = _unfetchable.value + id
            return
        }
        val region = settings.region
        viewModelScope.launch(Dispatchers.IO) {
            val d = Net.get(tmdbDetailsUrl(id, kind, key))?.let { parseDetails(it, id, kind, region) }
            withContext(Dispatchers.Main) {
                if (d == null || d.error) {
                    _unfetchable.value = _unfetchable.value + id
                } else {
                    _unfetchable.value = _unfetchable.value - id
                    storeDetails(d)
                    persistAfterDetails()
                }
            }
        }
    }

    private fun storeDetails(d: Details) {
        if (d.error) return
        details[d.id] = d
        // Fold richer year/genres into the catalog row so filters see them.
        movies = movies.map { if (it.id == d.id) it.copy(year = if (it.year == 0) d.year else it.year, genres = it.genres.ifEmpty { d.genres }) else it }
        series = series.map { if (it.id == d.id) it.copy(year = if (it.year == 0) d.year else it.year, genres = it.genres.ifEmpty { d.genres }) else it }
    }

    private fun persistAfterDetails() {
        Repo.saveDetails(getApplication(), details.values)
        Repo.saveItems(getApplication(), "movies.json", movies)
        Repo.saveItems(getApplication(), "series.json", series)
        _details.value = HashMap(details)
        recompute()
    }

    // ---- search ----
    fun setSearchQuery(q: String) { _search.value = _search.value.copy(query = q) }
    fun runSearch() {
        val key = settings.tmdbKey
        val q = _search.value.query.trim()
        if (key.isEmpty() || q.isEmpty()) return
        _search.value = _search.value.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val results = Net.get(tmdbSearchUrl(q, key))?.let { parseSearch(it) } ?: emptyList()
            withContext(Dispatchers.Main) { _search.value = _search.value.copy(results = results, busy = false) }
        }
    }
    fun clearSearch() { _search.value = SearchState() }

    /** Add a search hit to the catalog (by its kind) and fetch its details. */
    /** The catalog's own row for a title, which carries the year and
     *  genres a search hit does not. */
    fun catalogRow(id: String, kind: String): ListItem? =
        (if (kind == "movie") movies else series).find { it.id == id }

    fun addToCatalog(item: ListItem) {
        if (catalogRow(item.id, item.kind) != null) return
        if (item.kind == "movie") movies = movies + item else series = series + item
        Repo.saveItems(getApplication(), "movies.json", movies)
        Repo.saveItems(getApplication(), "series.json", series)
        recompute()
        fetchDetails(item.id, item.kind)
        // Publish straight away: a title added here is exactly the kind of
        // thing the desktop wants, and waiting for the next resume to say
        // so is a delay with no purpose.
        val uri = settings.syncTreeUri
        if (uri.isNotEmpty()) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    ShareRepo.saveMine(getApplication(), uri, Catalog(movies, series))
                }
            }
        }
    }

    fun clearStatus() { if (_ui.value.status != null) status(null) }
}
