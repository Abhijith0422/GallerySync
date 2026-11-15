package com.gallerysync.app.data.mediastore

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.gallerysync.app.data.localdb.DbHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PagingSource for the local gallery grid that pages both images and videos
 * from MediaStore using [MediaStoreScanner].
 */
class LocalPhotoSource(private val context: Context) : PagingSource<Int, LocalUiPhoto>() {

    override val jumpingSupported: Boolean = true

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LocalUiPhoto> {
        val page = params.key ?: 1
        val pageSize = params.loadSize
        val offset = (page - 1) * pageSize

        return try {
            val pagePhotoList = withContext(Dispatchers.IO) {
                val scannedItems = MediaStoreScanner.scanBatch(
                    context = context,
                    limit = pageSize,
                    offset = offset
                )

                if (scannedItems.isEmpty()) {
                    return@withContext emptyList<LocalUiPhoto>()
                }

                val tempPhotos = scannedItems.map { it.toLocalUiPhoto() }
                val localIds = tempPhotos.map { it.localId }

                // Batch attach remoteId from Room database
                val remotePhotos = DbHolder.database.photoDao().getRemoteIdsForLocals(localIds)
                val remoteIdMap = remotePhotos.associate { it.localId to it.remoteId }

                tempPhotos.map { photo ->
                    val remoteId = remoteIdMap[photo.localId]
                    if (remoteId != null) photo.copy(remoteId = remoteId) else photo
                }
            }

            val prevKey = if (page > 1) page - 1 else null
            val nextKey = if (pagePhotoList.size < pageSize) null else page + 1
            LoadResult.Page(pagePhotoList, prevKey, nextKey)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, LocalUiPhoto>): Int? {
        val anchor = state.anchorPosition ?: return null
        val closest = state.closestPageToPosition(anchor)
        return closest?.prevKey?.plus(1) ?: closest?.nextKey?.minus(1)
    }
}
