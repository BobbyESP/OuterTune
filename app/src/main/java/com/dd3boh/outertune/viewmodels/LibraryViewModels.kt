@file:OptIn(ExperimentalCoroutinesApi::class)

package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import com.zionhuang.innertube.YouTube
import com.dd3boh.outertune.constants.*
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.reversed
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.models.DirectoryTree
import com.dd3boh.outertune.ui.utils.DEFAULT_SCAN_PATH
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.refreshLocal
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.util.Stack
import javax.inject.Inject

@HiltViewModel
class LibrarySongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    private val syncUtils: SyncUtils,
) : ViewModel() {

    /**
     * The top of the stack is the folder that the page will render.
     * Clicking on a folder pushes, while the back button pops.
     */
    var folderPositionStack = Stack<DirectoryTree>()
    val databaseLink = database

    val allSongs = syncAllSongs(context, database, downloadUtil)

    private val scanPaths = context.dataStore[ScanPathsKey]?: DEFAULT_SCAN_PATH
    private val excludedScanPaths = context.dataStore[ExcludedScanPathsKey]?: ""
    val localSongDirectoryTree =
        refreshLocal(database, scanPaths.split('\n'), excludedScanPaths.split('\n'))

    val inLocal = mutableStateOf(false)

    fun syncLibrarySongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLibrarySongs() }
    }

    fun syncLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedSongs() }
    }


    /**
     * Get local songs
     *
     * @return DirectoryTree
     */
    fun getLocalSongs(database: MusicDatabase): MutableStateFlow<DirectoryTree> {
        val directoryStructure =
            refreshLocal(database, scanPaths.split('\n'),
                excludedScanPaths.split('\n')).value

        localSongDirectoryTree.value = directoryStructure
        return MutableStateFlow(directoryStructure)
    }


    fun syncAllSongs(context: Context, database: MusicDatabase, downloadUtil: DownloadUtil): StateFlow<List<Song>> {

        return context.dataStore.data
                .map {
                    Triple(
                            it[SongFilterKey].toEnum(SongFilter.LIKED),
                            it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                            (it[SongSortDescendingKey] ?: true)
                    )
                }
                .distinctUntilChanged()
                .flatMapLatest { (filter, sortType, descending) ->
                    when (filter) {
                        SongFilter.LIBRARY -> database.songs(sortType, descending)
                        SongFilter.LIKED -> database.likedSongs(sortType, descending)
                        SongFilter.DOWNLOADED -> downloadUtil.downloads.flatMapLatest { downloads ->
                            database.allSongs()
                                    .flowOn(Dispatchers.IO)
                                    .map { songs ->
                                        songs.filter {
                                            // show local songs as under downloaded for now
                                            downloads[it.id]?.state == Download.STATE_COMPLETED || it.song.isLocal
                                        }
                                    }
                                    .map { songs ->
                                        when (sortType) {
                                            SongSortType.CREATE_DATE -> songs.sortedBy { downloads[it.id]?.updateTimeMs ?: 0L }
                                            SongSortType.NAME -> songs.sortedBy { it.song.title }
                                            SongSortType.ARTIST -> songs.sortedBy { song ->
                                                song.artists.joinToString(separator = "") { it.name }
                                            }

                                            SongSortType.PLAY_TIME -> songs.sortedBy { it.song.totalPlayTime }
                                        }.reversed(descending)
                                    }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }
}

@HiltViewModel
class LibraryArtistsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val allArtists = context.dataStore.data
        .map {
            Triple(
                it[ArtistFilterKey].toEnum(ArtistFilter.LIKED),
                it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE),
                it[ArtistSortDescendingKey] ?: true
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending) ->
            when (filter) {
                ArtistFilter.LIBRARY -> database.artists(sortType, descending)
                ArtistFilter.LIKED -> database.artistsBookmarked(sortType, descending)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() { viewModelScope.launch(Dispatchers.IO) { syncUtils.syncArtistsSubscriptions() } }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(it.lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10)
                    }
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val allAlbums = context.dataStore.data
        .map {
            Triple(
                it[AlbumFilterKey].toEnum(AlbumFilter.LIKED),
                it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE),
                it[AlbumSortDescendingKey] ?: true
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (filter, sortType, descending) ->
            when (filter) {
                AlbumFilter.LIBRARY -> database.albums(sortType, descending)
                AlbumFilter.LIKED -> database.albumsLiked(sortType, descending)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() { viewModelScope.launch(Dispatchers.IO) { syncUtils.syncLikedAlbums() } }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            allAlbums.collect { albums ->
                albums.filter {
                    it.album.songCount == 0
                }.forEach { album ->
                    YouTube.album(album.id).onSuccess { albumPage ->
                        database.query {
                            update(album.album, albumPage)
                        }
                    }.onFailure {
                        reportException(it)
                        if (it.message?.contains("NOT_FOUND") == true) {
                            database.query {
                                delete(album.album)
                            }
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class LibraryPlaylistsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    val allPlaylists = context.dataStore.data
        .map {
            it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE) to (it[PlaylistSortDescendingKey] ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.playlists(sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun sync() { viewModelScope.launch(Dispatchers.IO) { syncUtils.syncSavedPlaylists() } }
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    var artists = database.artistsBookmarked(ArtistSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var albums = database.albumsLiked(AlbumSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    var playlists = database.playlists(PlaylistSortType.CREATE_DATE, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allItems = context.dataStore.data
        .map {
            it[LibrarySortTypeKey].toEnum(LibrarySortType.CREATE_DATE) to (it[LibrarySortDescendingKey]?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            combine(artists, albums, playlists) { artists, albums, playlists ->
                val items = artists + albums + playlists
                items.sortedBy { item ->
                    when (sortType) {
                        LibrarySortType.CREATE_DATE -> when (item) {
                            is Album -> item.album.bookmarkedAt
                            is Artist -> item.artist.bookmarkedAt
                            is Playlist -> item.playlist.bookmarkedAt
                            else -> LocalDateTime.now()
                        }

                        else -> when (item) {
                            is Album -> item.album.title
                            is Artist -> item.artist.name
                            is Playlist -> item.playlist.name
                            else -> ""
                        }
                    }.toString()
                }.let { if (descending) it.reversed() else it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class ArtistSongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs = context.dataStore.data
        .map {
            it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to (it[ArtistSongSortDescendingKey] ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.artistSongs(artistId, sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
