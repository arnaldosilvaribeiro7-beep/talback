package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.Friendship
import com.example.data.model.UserProfile
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date

private val Context.friendsCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "talkback_friends_cache")

@Serializable
data class CachedFriendEntry(
    val friendshipId: String,
    val participantUids: List<String>,
    val userAUid: String,
    val userAName: String,
    val userAUsername: String,
    val userAAvatarUrl: String? = null,
    val userBUid: String,
    val userBName: String,
    val userBUsername: String,
    val userBAvatarUrl: String? = null,
    val peerUid: String,
    val peerDisplayName: String,
    val peerUsername: String,
    val peerAvatarUrl: String? = null,
    val peerBio: String = "",
    val peerStatusText: String = "",
    val peerPresence: String = "offline",
    val createdAtEpochMs: Long = 0L,
    val syncedAtEpochMs: Long = 0L
) {
    fun toFriendship(): Friendship = Friendship(
        id = friendshipId,
        participantUids = participantUids,
        userAUid = userAUid,
        userAName = userAName,
        userAUsername = userAUsername,
        userAAvatarUrl = userAAvatarUrl,
        userBUid = userBUid,
        userBName = userBName,
        userBUsername = userBUsername,
        userBAvatarUrl = userBAvatarUrl,
        createdAt = createdAtEpochMs.takeIf { it > 0L }?.let { Timestamp(Date(it)) }
    )

    fun toPeerProfileSummary(): UserProfile = UserProfile(
        id = peerUid,
        userId = peerUid,
        username = peerUsername,
        displayName = peerDisplayName,
        bio = peerBio,
        statusText = peerStatusText,
        presence = peerPresence,
        avatarUrl = peerAvatarUrl
    )
}

/**
 * Persists the user's friends list and local favorite pins partially on-device via DataStore
 * so added friends load immediately on cold start, remain browsable offline, and keep
 * lightweight local preferences without extra Firestore writes.
 */
class LocalFriendsCache(context: Context) {
    private val dataStore = context.applicationContext.friendsCacheDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun friendsKey(userId: String) = stringPreferencesKey("friends_entries_$userId")
    private fun favoritesKey(userId: String) = stringPreferencesKey("favorite_peers_$userId")

    fun observeCachedFriendships(userId: String): Flow<List<Friendship>> =
        observeCachedEntries(userId).map { entries -> entries.map { it.toFriendship() } }

    fun observeCachedEntries(userId: String): Flow<List<CachedFriendEntry>> =
        dataStore.data.map { prefs -> decodeEntries(prefs[friendsKey(userId)]) }

    fun observeFavoritePeerUids(userId: String): Flow<Set<String>> =
        dataStore.data.map { prefs -> decodeFavorites(prefs[favoritesKey(userId)]) }

    suspend fun getCachedFriendships(userId: String): List<Friendship> =
        observeCachedFriendships(userId).first()

    suspend fun getCachedPeerProfiles(userId: String): Map<String, UserProfile> =
        observeCachedEntries(userId).first().associate { it.peerUid to it.toPeerProfileSummary() }

    suspend fun syncFriendships(userId: String, friendships: List<Friendship>) {
        if (userId.isBlank()) return
        val nowMs = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val existingById = decodeEntries(prefs[friendsKey(userId)]).associateBy { it.friendshipId }
            val merged = friendships.map { f ->
                val prev = existingById[f.id]
                val peerUid = f.peerUid(userId)
                CachedFriendEntry(
                    friendshipId = f.id,
                    participantUids = f.participantUids,
                    userAUid = f.userAUid,
                    userAName = f.userAName,
                    userAUsername = f.userAUsername,
                    userAAvatarUrl = f.userAAvatarUrl,
                    userBUid = f.userBUid,
                    userBName = f.userBName,
                    userBUsername = f.userBUsername,
                    userBAvatarUrl = f.userBAvatarUrl,
                    peerUid = peerUid,
                    peerDisplayName = prev?.peerDisplayName?.takeIf { it.isNotBlank() } ?: f.peerName(userId),
                    peerUsername = prev?.peerUsername?.takeIf { it.isNotBlank() } ?: f.peerUsername(userId),
                    peerAvatarUrl = prev?.peerAvatarUrl ?: f.peerAvatarUrl(userId),
                    peerBio = prev?.peerBio ?: "",
                    peerStatusText = prev?.peerStatusText ?: "",
                    peerPresence = prev?.peerPresence ?: "offline",
                    createdAtEpochMs = f.createdAt?.toDate()?.time ?: prev?.createdAtEpochMs ?: nowMs,
                    syncedAtEpochMs = nowMs
                )
            }
            prefs[friendsKey(userId)] = json.encodeToString(merged)
        }
    }

    suspend fun upsertFriendship(userId: String, friendship: Friendship) {
        if (userId.isBlank()) return
        val nowMs = System.currentTimeMillis()
        dataStore.edit { prefs ->
            val current = decodeEntries(prefs[friendsKey(userId)]).toMutableList()
            val idx = current.indexOfFirst { it.friendshipId == friendship.id }
            val prev = if (idx >= 0) current[idx] else null
            val entry = CachedFriendEntry(
                friendshipId = friendship.id,
                participantUids = friendship.participantUids,
                userAUid = friendship.userAUid,
                userAName = friendship.userAName,
                userAUsername = friendship.userAUsername,
                userAAvatarUrl = friendship.userAAvatarUrl,
                userBUid = friendship.userBUid,
                userBName = friendship.userBName,
                userBUsername = friendship.userBUsername,
                userBAvatarUrl = friendship.userBAvatarUrl,
                peerUid = friendship.peerUid(userId),
                peerDisplayName = prev?.peerDisplayName?.takeIf { it.isNotBlank() } ?: friendship.peerName(userId),
                peerUsername = prev?.peerUsername?.takeIf { it.isNotBlank() } ?: friendship.peerUsername(userId),
                peerAvatarUrl = prev?.peerAvatarUrl ?: friendship.peerAvatarUrl(userId),
                peerBio = prev?.peerBio ?: "",
                peerStatusText = prev?.peerStatusText ?: "",
                peerPresence = prev?.peerPresence ?: "offline",
                createdAtEpochMs = friendship.createdAt?.toDate()?.time ?: prev?.createdAtEpochMs ?: nowMs,
                syncedAtEpochMs = nowMs
            )
            if (idx >= 0) current[idx] = entry else current.add(entry)
            prefs[friendsKey(userId)] = json.encodeToString(current)
        }
    }

    suspend fun removeFriendship(userId: String, friendshipId: String) {
        if (userId.isBlank()) return
        dataStore.edit { prefs ->
            val current = decodeEntries(prefs[friendsKey(userId)])
            val removedPeer = current.firstOrNull { it.friendshipId == friendshipId }?.peerUid
            val filtered = current.filterNot { it.friendshipId == friendshipId }
            prefs[friendsKey(userId)] = json.encodeToString(filtered)
            if (removedPeer != null) {
                val favs = decodeFavorites(prefs[favoritesKey(userId)]) - removedPeer
                prefs[favoritesKey(userId)] = json.encodeToString(favs.toList())
            }
        }
    }

    suspend fun updateCachedPeerProfile(userId: String, profile: UserProfile) {
        if (userId.isBlank() || profile.userId.isBlank()) return
        dataStore.edit { prefs ->
            val current = decodeEntries(prefs[friendsKey(userId)])
            var changed = false
            val updated = current.map { entry ->
                if (entry.peerUid == profile.userId) {
                    changed = true
                    entry.copy(
                        peerDisplayName = profile.displayName,
                        peerUsername = profile.username,
                        peerAvatarUrl = profile.avatarUrl,
                        peerBio = profile.bio,
                        peerStatusText = profile.statusText,
                        peerPresence = profile.presence
                    )
                } else {
                    entry
                }
            }
            if (changed) {
                prefs[friendsKey(userId)] = json.encodeToString(updated)
            }
        }
    }

    suspend fun toggleFavoritePeer(userId: String, peerUid: String): Boolean {
        if (userId.isBlank() || peerUid.isBlank()) return false
        var nowFavorite = false
        dataStore.edit { prefs ->
            val favs = decodeFavorites(prefs[favoritesKey(userId)]).toMutableSet()
            nowFavorite = if (favs.contains(peerUid)) {
                favs.remove(peerUid)
                false
            } else {
                favs.add(peerUid)
                true
            }
            prefs[favoritesKey(userId)] = json.encodeToString(favs.toList())
        }
        return nowFavorite
    }

    private fun decodeEntries(raw: String?): List<CachedFriendEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CachedFriendEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeFavorites(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }
}
