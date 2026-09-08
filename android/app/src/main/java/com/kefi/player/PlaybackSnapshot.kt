package com.kefi.player

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * Builds the app's one playback [PlayerController.Snapshot] from ANY [Player].
 *
 * Extracted out of `PlayerController` (where it used to be a `MediaController`-only private
 * extension) because the snapshot now has two producers, not one: the app-side
 * `MediaController` — which only exists while a WebView does — and `PlaybackService`'s own
 * `ExoPlayer`, which is the only observer that survives the app being closed. Both must
 * produce byte-identical snapshots: the same struct feeds the JS `playerState` event, the
 * home-screen widget, and the persisted queue.
 *
 * The ordering of the metadata fallbacks below is load-bearing and has bitten in both
 * directions — see the comment inline.
 */
internal fun Player.playbackSnapshot(): PlayerController.Snapshot {
    val item = currentMediaItem
    val rawDuration = duration
    // The CURRENT ITEM's own metadata first, the player's combined metadata second.
    //
    // Items used to be built from bare uris and carried no metadata at all, so the item alone
    // reported null for every queue-driven track — hence reading the combined value. But the
    // combined value is updated ASYNCHRONOUSLY, one beat after the current item changes, so
    // with items that DO carry metadata (TrackSource.mediaItemsFor) it briefly reports the
    // PREVIOUS track: a snapshot taken right after setQueue returned the last-played title next
    // to the new mediaId. The item is always in sync with mediaId, so it leads; combined stays
    // as the fallback for anything MediaStore does not know (a file:// uri), where the
    // extractor is the only source of a title.
    val combined = mediaMetadata
    return PlayerController.Snapshot(
        mediaId = item?.mediaId?.takeIf { it.isNotEmpty() },
        uri = item?.localConfiguration?.uri?.toString(),
        title = (item?.mediaMetadata?.title ?: combined.title)?.toString(),
        artist = (
            item?.mediaMetadata?.artist
                ?: item?.mediaMetadata?.albumArtist
                ?: combined.artist
                ?: combined.albumArtist
            )?.toString(),
        currentPosition = currentPosition,
        duration = if (rawDuration == C.TIME_UNSET) 0L else rawDuration,
        isPlaying = isPlaying,
        shuffleModeEnabled = shuffleModeEnabled,
        repeatMode = repeatMode,
        queueLength = mediaItemCount,
        currentIndex = currentMediaItemIndex,
    )
}

/** The uris currently in [Player]'s timeline, in timeline order. Empty when nothing is queued. */
internal fun Player.queueUris(): List<String> =
    (0 until mediaItemCount).mapNotNull { getMediaItemAt(it).localConfiguration?.uri?.toString() }
