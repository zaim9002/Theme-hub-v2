package com.example

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class ProfileInitResult(
    val displayName: String,
    val username: String,
    val usernameChosen: Boolean,
    val photoURL: String,
    val bannerURL: String,
    val bio: String,
    val links: List<String>,
    val profileAccent: String,
    val profileGradient: String,
    val hideCommentsCount: Boolean,
    val badges: List<String>,
    val frame: String,
    val customTitle: String,
    val createdAt: Long,
    val blocked: Boolean,
    val publisher: Boolean,
    val isAdmin: Boolean,
    val isOwner: Boolean,
    val error: String = ""
)

class CommunityFirestoreManager(
    private val activity: MainActivity,
    private val scope: CoroutineScope
) {
    private val db get() = Firebase.firestore
    private val auth get() = Firebase.auth

    private var unreadBadgeListener: ListenerRegistration? = null
    private var pendingSubmissionsBadgeListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private var inboxListener: ListenerRegistration? = null
    private var chatConvListener: ListenerRegistration? = null
    private var chatMessagesListener: ListenerRegistration? = null

    private var lastMessageSentAtMs: Long = 0L
    private val cachedAdminUids = mutableSetOf<String>()
    private var cachedOwnerUid: String = ""

    companion object {
        private const val TAG = "ThemeHubCommunity"
        private const val MAX_IMAGE_DATA_URL_LEN = 220_000
        private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,20}$")
        private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
        private val ALLOWED_FRAMES = setOf("", "gold-admin", "neon", "aurora")
        private val ALLOWED_BADGES = setOf("موثّق", "ناشر موثوق", "داعم", "مبدع")
    }

    fun detachAllListeners() {
        runCatching { unreadBadgeListener?.remove() }
        runCatching { pendingSubmissionsBadgeListener?.remove() }
        runCatching { notificationsListener?.remove() }
        runCatching { inboxListener?.remove() }
        stopConversationChatListener()
        unreadBadgeListener = null
        pendingSubmissionsBadgeListener = null
        notificationsListener = null
        inboxListener = null
    }

    private fun sha256LowerHex(input: String): String {
        val clean = input.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractStringList(snap: DocumentSnapshot, field: String): List<String> {
        val raw = snap.get(field) as? List<*> ?: return emptyList()
        return raw.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    private fun emitCommunityError(context: String, throwable: Throwable) {
        val msg = AuthErrorFormatter.format(throwable)
        Log.e(TAG, "Community error [$context]: $msg", throwable)
        val payload = JSONObject().apply {
            put("ok", false)
            put("context", context)
            put("error", msg)
        }.toString()
        activity.evaluateJs("window.onCommunityError?.($payload);")
    }

    internal suspend fun writeAuditLog(
        action: String,
        targetType: String,
        targetId: String,
        details: String
    ) {
        val actor = auth.currentUser ?: return
        if (!activity.currentUserIsAdmin) return
        runCatching {
            val actorEmail = actor.email?.trim()?.lowercase().orEmpty()
            val entry = hashMapOf<String, Any>(
                "actorUid" to actor.uid,
                "actorLabel" to (actor.displayName?.takeIf { it.isNotBlank() } ?: actorEmail.substringBefore("@").ifBlank { actor.uid.take(8) }),
                "action" to action.take(60),
                "targetType" to targetType.take(40),
                "targetId" to targetId.take(120),
                "details" to details.take(300),
                "createdAt" to System.currentTimeMillis()
            )
            db.collection("auditLog").add(entry).await()
        }.onFailure { e ->
            Log.w(TAG, "auditLog write warning: ${e.message}")
        }
    }

    private suspend fun sendUserNotification(
        targetUid: String,
        type: String,
        title: String,
        body: String,
        submissionId: String = "",
        themeId: String = ""
    ) {
        if (targetUid.isBlank()) return
        runCatching {
            val notifData = hashMapOf<String, Any>(
                "type" to type,
                "title" to title.take(100),
                "body" to body.take(350),
                "submissionId" to submissionId,
                "themeId" to themeId,
                "read" to false,
                "createdAt" to System.currentTimeMillis()
            )
            db.collection("notifications").document(targetUid)
                .collection("items").add(notifData).await()
        }.onFailure { e ->
            Log.w(TAG, "sendUserNotification warning: ${e.message}")
        }
    }

    private suspend fun refreshSupportDirectory(force: Boolean = false) {
        if (!force && cachedAdminUids.isNotEmpty()) return
        runCatching {
            val snap = db.collection("config").document("support").get().await()
            if (snap.exists()) {
                cachedOwnerUid = snap.getString("ownerUid")?.trim().orEmpty()
                val rawList = snap.get("publicAdminUids") as? List<*>
                cachedAdminUids.clear()
                if (cachedOwnerUid.isNotEmpty()) cachedAdminUids.add(cachedOwnerUid)
                rawList?.forEach { item ->
                    val uid = (item as? String)?.trim().orEmpty()
                    if (uid.isNotEmpty()) cachedAdminUids.add(uid)
                }
            }
        }
    }

    suspend fun ensureUserProfileInitialized(
        user: FirebaseUser,
        isOwner: Boolean,
        isAllowlistedAdmin: Boolean
    ): ProfileInitResult {
        val rawEmail = user.email?.trim().orEmpty()
        val userEmail = rawEmail.lowercase()
        val fallbackName = if (isOwner) "المالك (zaim9002)" else (userEmail.substringBefore("@").ifBlank { "مستخدم Theme Hub" })
        val name = user.displayName?.takeIf { it.isNotBlank() } ?: fallbackName
        val photo = user.photoUrl?.toString().orEmpty()

        refreshSupportDirectory(force = true)

        if (isAllowlistedAdmin) {
            cachedAdminUids.add(user.uid)
            if (isOwner) cachedOwnerUid = user.uid
            val supportUpdate = hashMapOf<String, Any>(
                "publicAdminUids" to FieldValue.arrayUnion(user.uid),
                "updatedAt" to System.currentTimeMillis()
            )
            if (isOwner) {
                supportUpdate["ownerUid"] = user.uid
            }
            runCatching {
                db.collection("config").document("support")
                    .set(supportUpdate, SetOptions.merge())
                    .await()
            }.onFailure { e ->
                Log.w(TAG, "config/support sync note: ${e.message}")
            }
        }

        // Write hashed email lookup for private search (no raw email stored)
        if (userEmail.isNotEmpty()) {
            runCatching {
                val hash = sha256LowerHex(userEmail)
                db.collection("emailLookup").document(hash).set(
                    hashMapOf(
                        "uid" to user.uid,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()
            }.onFailure { e ->
                Log.w(TAG, "emailLookup sync note: ${e.message}")
            }
        }

        val userRef = db.collection("users").document(user.uid)
        var profileBlocked = false
        var profilePublisher = false
        var profileBio = ""
        var profileName = name
        var profilePhoto = photo
        var profileBanner = ""
        var profileUsername = ""
        var profileUsernameChosen = false
        var profileLinks = emptyList<String>()
        var profileAccent = ""
        var profileGradient = ""
        var profileHideCommentsCount = false
        var profileBadges = emptyList<String>()
        var profileFrame = ""
        var profileCustomTitle = ""
        var profileCreatedAt = System.currentTimeMillis()
        var initError = ""

        try {
            val userSnap = userRef.get().await()
            if (userSnap.exists()) {
                profileBlocked = userSnap.getBoolean("blocked") ?: false
                profilePublisher = userSnap.getBoolean("publisher") ?: false
                profileBio = userSnap.getString("bio") ?: ""
                profileName = userSnap.getString("displayName")?.takeIf { it.isNotBlank() } ?: name
                profilePhoto = userSnap.getString("photoURL")?.takeIf { it.isNotBlank() } ?: photo
                profileBanner = userSnap.getString("bannerURL") ?: ""
                profileUsername = (userSnap.getString("username") ?: "").trim().lowercase()
                val storedLower = (userSnap.getString("usernameLower") ?: "").trim().lowercase()
                val hasValidUname = USERNAME_REGEX.matches(profileUsername)
                profileUsernameChosen = if (!hasValidUname) {
                    false
                } else {
                    userSnap.getBoolean("usernameChosen") ?: true
                }
                profileLinks = extractStringList(userSnap, "links")
                profileAccent = userSnap.getString("profileAccent") ?: ""
                profileGradient = userSnap.getString("profileGradient") ?: ""
                profileHideCommentsCount = userSnap.getBoolean("hideCommentsCount") ?: false
                profileBadges = extractStringList(userSnap, "badges")
                profileFrame = userSnap.getString("frame") ?: ""
                profileCustomTitle = userSnap.getString("customTitle") ?: ""
                profileCreatedAt = userSnap.getLong("createdAt") ?: profileCreatedAt

                if (hasValidUname && storedLower != profileUsername) {
                    runCatching {
                        userRef.update("usernameLower", profileUsername, "updatedAt", System.currentTimeMillis()).await()
                    }
                }
            } else {
                // First sign-in: create base profile and prompt mandatory @username picker
                profileUsername = ""
                profileUsernameChosen = false
                val initialProfile = hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "username" to "",
                    "usernameLower" to "",
                    "usernameChosen" to false,
                    "displayName" to name.take(60),
                    "photoURL" to photo,
                    "bannerURL" to "",
                    "bio" to "",
                    "links" to emptyList<String>(),
                    "profileAccent" to "",
                    "profileGradient" to "",
                    "hideCommentsCount" to false,
                    "badges" to emptyList<String>(),
                    "frame" to "",
                    "customTitle" to "",
                    "createdAt" to profileCreatedAt,
                    "updatedAt" to profileCreatedAt,
                    "blocked" to false,
                    "publisher" to false
                )
                userRef.set(initialProfile).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            initError = AuthErrorFormatter.format(e)
            emitCommunityError("users_profile_init", e)
        }

        val isAdminEffective = isAllowlistedAdmin && !profileBlocked
        return ProfileInitResult(
            displayName = profileName,
            username = profileUsername,
            usernameChosen = profileUsernameChosen,
            photoURL = profilePhoto,
            bannerURL = profileBanner,
            bio = profileBio,
            links = profileLinks,
            profileAccent = profileAccent,
            profileGradient = profileGradient,
            hideCommentsCount = profileHideCommentsCount,
            badges = profileBadges,
            frame = profileFrame,
            customTitle = profileCustomTitle,
            createdAt = profileCreatedAt,
            blocked = profileBlocked,
            publisher = profilePublisher,
            isAdmin = isAdminEffective,
            isOwner = isOwner,
            error = initError
        )
    }

    fun claimInitialUsername(rawUsername: String, rawDisplayName: String) {
        val user = auth.currentUser
        if (user == null) {
            emitUsernameClaimResult(false, "", "", "يجب تسجيل الدخول أولاً")
            return
        }
        val cleanUname = rawUsername.trim().removePrefix("@").lowercase()
        if (!USERNAME_REGEX.matches(cleanUname)) {
            emitUsernameClaimResult(
                false, "", "",
                "اسم المستخدم يجب أن يكون 3 إلى 20 حرفاً إنجليزياً صغيراً أو أرقاماً أو _"
            )
            return
        }
        val cleanName = rawDisplayName.trim().take(60)
        scope.launch {
            try {
                val uid = user.uid
                val now = System.currentTimeMillis()
                val userRef = db.collection("users").document(uid)
                val finalDisplayName = db.runTransaction { tx ->
                    val userSnap = tx.get(userRef)
                    val oldUname = (userSnap.getString("username") ?: "").trim().lowercase()
                    val newUnameRef = db.collection("usernames").document(cleanUname)
                    val newUnameSnap = tx.get(newUnameRef)
                    if (newUnameSnap.exists() && newUnameSnap.getString("uid") != uid) {
                        throw IllegalStateException("اسم المستخدم @$cleanUname محجوز بالفعل، اختر اسماً آخر")
                    }
                    if (oldUname.isNotEmpty() && oldUname != cleanUname) {
                        val oldRef = db.collection("usernames").document(oldUname)
                        val oldSnap = tx.get(oldRef)
                        if (oldSnap.exists() && oldSnap.getString("uid") == uid) {
                            tx.delete(oldRef)
                        }
                    }
                    if (!newUnameSnap.exists()) {
                        tx.set(newUnameRef, hashMapOf("uid" to uid, "createdAt" to now))
                    }
                    val chosenName = cleanName.ifBlank { userSnap.getString("displayName") ?: (user.displayName ?: "عضو Theme Hub") }.take(60)
                    val updates = hashMapOf<String, Any>(
                        "uid" to uid,
                        "username" to cleanUname,
                        "usernameLower" to cleanUname,
                        "usernameChosen" to true,
                        "displayName" to chosenName,
                        "updatedAt" to now
                    )
                    if (!userSnap.exists()) {
                        updates["photoURL"] = user.photoUrl?.toString().orEmpty()
                        updates["bannerURL"] = ""
                        updates["bio"] = ""
                        updates["links"] = emptyList<String>()
                        updates["profileAccent"] = ""
                        updates["profileGradient"] = ""
                        updates["hideCommentsCount"] = false
                        updates["badges"] = emptyList<String>()
                        updates["frame"] = ""
                        updates["customTitle"] = ""
                        updates["createdAt"] = now
                        updates["blocked"] = false
                        updates["publisher"] = false
                    }
                    tx.set(userRef, updates, SetOptions.merge())
                    chosenName
                }.await()
                emitUsernameClaimResult(true, cleanUname, finalDisplayName, "")
                activity.sendToastToJs("✓ تم حجز اسم المستخدم @$cleanUname بنجاح")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = if (e is IllegalStateException) (e.message ?: "خطأ") else AuthErrorFormatter.format(e)
                emitUsernameClaimResult(false, "", "", msg)
            }
        }
    }

    private fun emitUsernameClaimResult(ok: Boolean, username: String, displayName: String, error: String) {
        val payload = JSONObject().apply {
            put("ok", ok)
            put("username", username)
            put("displayName", displayName)
            put("error", error)
        }.toString()
        activity.evaluateJs("window.onInitialUsernameClaimed?.($payload);")
    }

    fun updateMyProfileExtended(
        uid: String,
        displayName: String,
        rawUsername: String,
        bio: String,
        photoDataUrl: String,
        bannerDataUrl: String,
        extraJson: String
    ) {
        val user = auth.currentUser
        if (user == null || user.uid != uid) {
            emitProfileUpdateError("يجب تسجيل الدخول لحفظ التعديلات")
            return
        }
        if (activity.currentUserBlocked) {
            emitProfileUpdateError("تم حظر حسابك من تعديل الملف الشخصي")
            return
        }
        val cleanName = displayName.trim().ifBlank { user.displayName ?: "مستخدم Theme Hub" }.take(60)
        val cleanBio = bio.trim().take(150)
        val cleanPhoto = photoDataUrl.trim()
        val cleanBanner = bannerDataUrl.trim()
        val cleanUsername = rawUsername.trim().removePrefix("@").lowercase()

        if (!USERNAME_REGEX.matches(cleanUsername)) {
            emitProfileUpdateError("اسم المستخدم (@username) يجب أن يكون 3 إلى 20 حرفاً (a-z, 0-9, _) فقط")
            return
        }
        if (cleanPhoto.length > MAX_IMAGE_DATA_URL_LEN) {
            emitProfileUpdateError("حجم الصورة الشخصية المضغوطة يتجاوز الحد الأقصى (150KB WebP)")
            return
        }
        if (cleanBanner.length > MAX_IMAGE_DATA_URL_LEN) {
            emitProfileUpdateError("حجم صورة الغلاف (Banner) يتجاوز الحد الأقصى المسموح (155KB)")
            return
        }
        val isGifBanner = cleanBanner.startsWith("data:image/gif;", ignoreCase = true)
        if (isGifBanner && !activity.currentUserIsAdmin) {
            emitProfileUpdateError("خلفية الغلاف المتحركة GIF متاحة للمشرفين فقط")
            return
        }

        val extra = runCatching { JSONObject(extraJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val linksArr = extra.optJSONArray("links")
        val cleanLinks = mutableListOf<String>()
        if (linksArr != null) {
            for (i in 0 until linksArr.length()) {
                val u = linksArr.optString(i, "").trim()
                if (u.startsWith("https://") && u.length in 9..200 && cleanLinks.size < 3) {
                    cleanLinks.add(u)
                }
            }
        }
        val rawAccent = extra.optString("profileAccent", "").trim()
        val cleanAccent = if (rawAccent.isEmpty() || HEX_COLOR_REGEX.matches(rawAccent)) rawAccent else ""
        val hideCommentsCount = extra.optBoolean("hideCommentsCount", false)

        val adminFrameRaw = extra.optString("frame", "").trim()
        val adminCustomTitle = extra.optString("customTitle", "").trim().take(30)
        val adminGradientRaw = extra.optString("profileGradient", "").trim().take(160)

        scope.launch {
            try {
                val userRef = db.collection("users").document(uid)
                val now = System.currentTimeMillis()
                db.runTransaction { tx ->
                    val currentSnap = tx.get(userRef)
                    val oldUsername = (currentSnap.getString("username") ?: "").trim().lowercase()
                    if (cleanUsername != oldUsername) {
                        val newUnameRef = db.collection("usernames").document(cleanUsername)
                        val newUnameSnap = tx.get(newUnameRef)
                        if (newUnameSnap.exists() && newUnameSnap.getString("uid") != uid) {
                            throw IllegalStateException("اسم المستخدم @$cleanUsername محجوز بالفعل، اختر اسماً آخر")
                        }
                        if (oldUsername.isNotEmpty()) {
                            val oldUnameRef = db.collection("usernames").document(oldUsername)
                            val oldUnameSnap = tx.get(oldUnameRef)
                            if (oldUnameSnap.exists() && oldUnameSnap.getString("uid") == uid) {
                                tx.delete(oldUnameRef)
                            }
                        }
                        if (!newUnameSnap.exists()) {
                            tx.set(newUnameRef, hashMapOf("uid" to uid, "createdAt" to now))
                        }
                    }

                    val updates = hashMapOf<String, Any>(
                        "uid" to uid,
                        "displayName" to cleanName,
                        "username" to cleanUsername,
                        "usernameLower" to cleanUsername,
                        "usernameChosen" to true,
                        "bio" to cleanBio,
                        "photoURL" to cleanPhoto,
                        "bannerURL" to cleanBanner,
                        "links" to cleanLinks,
                        "profileAccent" to cleanAccent,
                        "hideCommentsCount" to hideCommentsCount,
                        "updatedAt" to now
                    )
                    if (!currentSnap.exists()) {
                        updates["profileGradient"] = ""
                        updates["badges"] = emptyList<String>()
                        updates["frame"] = ""
                        updates["customTitle"] = ""
                        updates["createdAt"] = now
                        updates["blocked"] = false
                        updates["publisher"] = false
                    }
                    if (activity.currentUserIsAdmin) {
                        if (extra.has("frame") && adminFrameRaw in ALLOWED_FRAMES) {
                            updates["frame"] = adminFrameRaw
                        }
                        if (extra.has("customTitle")) {
                            updates["customTitle"] = adminCustomTitle
                        }
                        if (extra.has("profileGradient")) {
                            updates["profileGradient"] = adminGradientRaw
                        }
                    }
                    tx.set(userRef, updates, SetOptions.merge())
                    null
                }.await()

                val updatedSnap = runCatching { userRef.get().await() }.getOrNull()
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("uid", uid)
                    put("displayName", cleanName)
                    put("username", cleanUsername)
                    put("bio", cleanBio)
                    put("photoURL", cleanPhoto)
                    put("bannerURL", cleanBanner)
                    put("links", JSONArray(cleanLinks))
                    put("profileAccent", cleanAccent)
                    put("hideCommentsCount", hideCommentsCount)
                    put("frame", updatedSnap?.getString("frame") ?: "")
                    put("customTitle", updatedSnap?.getString("customTitle") ?: "")
                    put("profileGradient", updatedSnap?.getString("profileGradient") ?: "")
                    put("badges", JSONArray(if (updatedSnap != null) extractStringList(updatedSnap, "badges") else emptyList<String>()))
                }.toString()
                activity.evaluateJs("window.onUserProfileUpdated?.($payload);")
                activity.sendToastToJs("✓ تم حفظ الملف الشخصي بنجاح")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitProfileUpdateError(
                    if (e is IllegalStateException) (e.message ?: "خطأ") else AuthErrorFormatter.format(e)
                )
            }
        }
    }

    fun updateMyProfile(
        uid: String,
        displayName: String,
        rawUsername: String,
        bio: String,
        photoDataUrl: String,
        bannerDataUrl: String
    ) {
        updateMyProfileExtended(uid, displayName, rawUsername, bio, photoDataUrl, bannerDataUrl, "{}")
    }

    private fun emitProfileUpdateError(errorMessage: String) {
        val payload = JSONObject().apply {
            put("ok", false)
            put("error", errorMessage)
        }.toString()
        activity.evaluateJs("window.onUserProfileUpdated?.($payload);")
    }

    fun fetchUserProfile(uid: String) {
        scope.launch {
            try {
                refreshSupportDirectory(force = false)
                val targetUid = if (uid == "__admin__") {
                    refreshSupportDirectory(force = true)
                    cachedOwnerUid.ifBlank { cachedAdminUids.firstOrNull().orEmpty() }
                } else {
                    uid.trim()
                }

                if (targetUid.isBlank()) {
                    val errPayload = JSONObject().apply {
                        put("ok", false)
                        put("error", "لم يقم المشرف بتفعيل ملفه العام بعد، يمكنك تسجيل الدخول كمشرف أولاً لتحديث سجل المشرفين.")
                    }.toString()
                    activity.evaluateJs("window.onUserProfileLoaded?.($errPayload);")
                    return@launch
                }

                val snap = db.collection("users").document(targetUid).get().await()
                if (!snap.exists()) {
                    val errPayload = JSONObject().apply {
                        put("ok", false)
                        put("uid", targetUid)
                        put("error", "تعذر العثور على الملف الشخصي لهذا المستخدم ($targetUid)")
                    }.toString()
                    activity.evaluateJs("window.onUserProfileLoaded?.($errPayload);")
                    return@launch
                }

                var commentsCount = 0L
                var commentsCountError = ""
                try {
                    val countAgg = db.collectionGroup("comments")
                        .whereEqualTo("uid", targetUid)
                        .count()
                        .get(AggregateSource.SERVER)
                        .await()
                    commentsCount = countAgg.count
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ce: Throwable) {
                    val isPrecond = (ce as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
                    if (isPrecond) {
                        commentsCount = runCatching {
                            val themeDocs = db.collection("themes").limit(35).get().await()
                            var total = 0L
                            for (td in themeDocs.documents) {
                                total += runCatching {
                                    td.reference.collection("comments")
                                        .whereEqualTo("uid", targetUid)
                                        .count()
                                        .get(AggregateSource.SERVER)
                                        .await()
                                        .count
                                }.getOrDefault(0L)
                            }
                            total
                        }.getOrDefault(0L)
                    } else {
                        commentsCountError = AuthErrorFormatter.format(ce)
                    }
                }

                val myUid = auth.currentUser?.uid
                var personalBlockedByMe = false
                if (!myUid.isNullOrBlank() && myUid != targetUid) {
                    personalBlockedByMe = runCatching {
                        db.collection("users").document(myUid)
                            .collection("blockedUsers").document(targetUid)
                            .get().await().exists()
                    }.getOrDefault(false)
                }

                val targetIsAdmin = cachedAdminUids.contains(targetUid) ||
                    (myUid == targetUid && activity.currentUserIsAdmin)

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("uid", snap.getString("uid") ?: targetUid)
                    put("displayName", snap.getString("displayName") ?: "مستخدم Theme Hub")
                    put("username", snap.getString("username") ?: "")
                    put("photoURL", snap.getString("photoURL") ?: "")
                    put("bannerURL", snap.getString("bannerURL") ?: "")
                    put("bio", snap.getString("bio") ?: "")
                    put("links", JSONArray(extractStringList(snap, "links")))
                    put("profileAccent", snap.getString("profileAccent") ?: "")
                    put("profileGradient", snap.getString("profileGradient") ?: "")
                    put("hideCommentsCount", snap.getBoolean("hideCommentsCount") ?: false)
                    put("badges", JSONArray(extractStringList(snap, "badges")))
                    put("frame", snap.getString("frame") ?: "")
                    put("customTitle", snap.getString("customTitle") ?: "")
                    put("createdAt", snap.getLong("createdAt") ?: 0L)
                    put("blocked", snap.getBoolean("blocked") ?: false)
                    put("publisher", snap.getBoolean("publisher") ?: false)
                    put("isAdmin", targetIsAdmin)
                    put("commentsCount", commentsCount)
                    put("commentsCountError", commentsCountError)
                    put("personalBlockedByMe", personalBlockedByMe)
                }.toString()
                activity.evaluateJs("window.onUserProfileLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errPayload = JSONObject().apply {
                    put("ok", false)
                    put("uid", uid)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onUserProfileLoaded?.($errPayload);")
            }
        }
    }

    fun searchMembers(rawQuery: String) {
        val myUid = auth.currentUser?.uid
        if (myUid.isNullOrBlank()) {
            val payload = JSONObject().apply {
                put("ok", false)
                put("error", "يجب تسجيل الدخول أولاً للبحث عن الأعضاء")
            }.toString()
            activity.evaluateJs("window.onMemberSearchResults?.($payload);")
            return
        }
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            val payload = JSONObject().apply {
                put("ok", true)
                put("query", "")
                put("users", JSONArray())
            }.toString()
            activity.evaluateJs("window.onMemberSearchResults?.($payload);")
            return
        }

        scope.launch {
            try {
                refreshSupportDirectory(force = false)
                val foundDocs = mutableListOf<DocumentSnapshot>()
                val isEmailQuery = !trimmed.startsWith("@") && trimmed.contains("@")

                if (isEmailQuery) {
                    val hash = sha256LowerHex(trimmed)
                    val lookupSnap = db.collection("emailLookup").document(hash).get().await()
                    val foundUid = lookupSnap.getString("uid")?.trim().orEmpty()
                    if (foundUid.isNotEmpty() && foundUid != myUid) {
                        val userSnap = db.collection("users").document(foundUid).get().await()
                        if (userSnap.exists()) foundDocs.add(userSnap)
                    }
                } else {
                    val prefix = trimmed.removePrefix("@").lowercase().replace(Regex("[^a-z0-9_]"), "")
                    if (prefix.length >= 2) {
                        val prefixSnap = db.collection("users")
                            .orderBy("usernameLower")
                            .startAt(prefix)
                            .endAt(prefix + "\uf8ff")
                            .limit(10)
                            .get().await()
                        foundDocs.addAll(prefixSnap.documents)

                        // Fallback exact username lookup for any legacy doc lacking usernameLower
                        if (foundDocs.isEmpty() && USERNAME_REGEX.matches(prefix)) {
                            val exactUnameSnap = db.collection("usernames").document(prefix).get().await()
                            val exactUid = exactUnameSnap.getString("uid")?.trim().orEmpty()
                            if (exactUid.isNotEmpty() && exactUid != myUid) {
                                val uSnap = db.collection("users").document(exactUid).get().await()
                                if (uSnap.exists()) foundDocs.add(uSnap)
                            }
                        }
                    }
                }

                val arr = JSONArray()
                val seen = mutableSetOf<String>()
                for (d in foundDocs) {
                    val uid = d.getString("uid") ?: d.id
                    if (uid == myUid || !seen.add(uid)) continue
                    arr.put(JSONObject().apply {
                        put("uid", uid)
                        put("displayName", d.getString("displayName") ?: "عضو Theme Hub")
                        put("username", d.getString("username") ?: "")
                        put("photoURL", d.getString("photoURL") ?: "")
                        put("badges", JSONArray(extractStringList(d, "badges")))
                        put("frame", d.getString("frame") ?: "")
                        put("customTitle", d.getString("customTitle") ?: "")
                        put("isAdmin", cachedAdminUids.contains(uid))
                        put("blocked", d.getBoolean("blocked") ?: false)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("query", trimmed)
                    put("users", arr)
                }.toString()
                activity.evaluateJs("window.onMemberSearchResults?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("query", trimmed)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onMemberSearchResults?.($payload);")
            }
        }
    }

    fun togglePersonalBlockUser(targetUid: String, blocked: Boolean) {
        val myUid = auth.currentUser?.uid
        if (myUid.isNullOrBlank()) {
            activity.sendToastToJs("يجب تسجيل الدخول أولاً")
            return
        }
        if (targetUid.isBlank() || targetUid == myUid) return
        scope.launch {
            try {
                val ref = db.collection("users").document(myUid)
                    .collection("blockedUsers").document(targetUid)
                if (blocked) {
                    ref.set(hashMapOf("uid" to targetUid, "createdAt" to System.currentTimeMillis())).await()
                } else {
                    ref.delete().await()
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("uid", targetUid)
                    put("blocked", blocked)
                }.toString()
                activity.evaluateJs("window.onPersonalBlockToggled?.($payload);")
                activity.sendToastToJs(if (blocked) "تم حظر الرسائل من هذا المستخدم" else "تم إلغاء الحظر الشخصي")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("uid", targetUid)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onPersonalBlockToggled?.($payload);")
            }
        }
    }

    fun adminSetUserBlocked(targetUid: String, blocked: Boolean) {
        if (!activity.currentUserIsAdmin) {
            activity.sendToastToJs("غير مصرح: الحظر العام متاح للمشرفين فقط")
            return
        }
        scope.launch {
            try {
                db.collection("users").document(targetUid)
                    .update("blocked", blocked, "updatedAt", System.currentTimeMillis())
                    .await()
                writeAuditLog(if (blocked) "block_user" else "unblock_user", "user", targetUid, "blocked=$blocked")
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("uid", targetUid)
                    put("blocked", blocked)
                }.toString()
                activity.evaluateJs("window.onUserBlockToggled?.($payload);")
                activity.sendToastToJs(if (blocked) "تم حظر المستخدم من التفاعل" else "تم إلغاء حظر المستخدم")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("uid", targetUid)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onUserBlockToggled?.($payload);")
            }
        }
    }

    fun adminSetUserPublisher(targetUid: String, publisher: Boolean) {
        if (!activity.currentUserIsAdmin) {
            activity.sendToastToJs("غير مصرح: اعتماد الناشرين متاح للمشرفين فقط")
            return
        }
        scope.launch {
            try {
                db.collection("users").document(targetUid)
                    .update("publisher", publisher, "updatedAt", System.currentTimeMillis())
                    .await()
                writeAuditLog(if (publisher) "grant_publisher" else "revoke_publisher", "user", targetUid, "publisher=$publisher")
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("uid", targetUid)
                    put("publisher", publisher)
                }.toString()
                activity.evaluateJs("window.onUserPublisherToggled?.($payload);")
                activity.sendToastToJs(if (publisher) "✓ تم اعتماد المستخدم كناشر ثيمات" else "تم سحب صلاحية الناشر")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("uid", targetUid)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onUserPublisherToggled?.($payload);")
            }
        }
    }

    // ---------------- BADGE & NOTIFICATION LISTENERS ----------------
    fun startUnreadMessagesListener(uid: String) {
        if (uid.isBlank()) return
        runCatching { unreadBadgeListener?.remove() }
        unreadBadgeListener = db.collection("conversations")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    emitCommunityError("unread_messages", err)
                    return@addSnapshotListener
                }
                var unreadCount = 0
                snap?.documents?.forEach { doc ->
                    val unreadMap = doc.get("unreadBy") as? Map<*, *>
                    if (unreadMap?.get(uid) == true) {
                        unreadCount++
                    }
                }
                activity.evaluateJs("window.onUnreadMessagesCount?.($unreadCount);")
            }
    }

    fun startNotificationsListener(uid: String) {
        runCatching { notificationsListener?.remove() }
        notificationsListener = db.collection("notifications").document(uid)
            .collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    val payload = JSONObject().apply {
                        put("ok", false)
                        put("error", AuthErrorFormatter.format(err))
                    }.toString()
                    activity.evaluateJs("window.onNotificationsUpdated?.($payload);")
                    return@addSnapshotListener
                }
                val arr = JSONArray()
                var unread = 0
                snap?.documents?.forEach { d ->
                    val isRead = d.getBoolean("read") ?: false
                    if (!isRead) unread++
                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("type", d.getString("type") ?: "info")
                        put("title", d.getString("title") ?: "")
                        put("body", d.getString("body") ?: "")
                        put("submissionId", d.getString("submissionId") ?: "")
                        put("themeId", d.getString("themeId") ?: "")
                        put("read", isRead)
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("unreadCount", unread)
                    put("items", arr)
                }.toString()
                activity.evaluateJs("window.onNotificationsUpdated?.($payload);")
            }
    }

    fun markNotificationRead(notifId: String) {
        val uid = auth.currentUser?.uid ?: return
        if (notifId.isBlank()) return
        scope.launch {
            runCatching {
                db.collection("notifications").document(uid)
                    .collection("items").document(notifId)
                    .update("read", true).await()
            }
        }
    }

    fun markAllNotificationsRead() {
        val uid = auth.currentUser?.uid ?: return
        scope.launch {
            runCatching {
                val snap = db.collection("notifications").document(uid)
                    .collection("items")
                    .whereEqualTo("read", false)
                    .limit(30)
                    .get().await()
                if (!snap.isEmpty) {
                    val batch = db.batch()
                    snap.documents.forEach { batch.update(it.reference, "read", true) }
                    batch.commit().await()
                }
            }
        }
    }

    fun startPendingSubmissionsBadgeListener(isAdmin: Boolean) {
        runCatching { pendingSubmissionsBadgeListener?.remove() }
        if (!isAdmin) {
            activity.evaluateJs("window.onPendingSubmissionsCount?.(0);")
            return
        }
        pendingSubmissionsBadgeListener = db.collection("submissions")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    emitCommunityError("pending_submissions_badge", err)
                    return@addSnapshotListener
                }
                val count = snap?.size() ?: 0
                activity.evaluateJs("window.onPendingSubmissionsCount?.($count);")
            }
    }

    // ---------------- 1-TO-1 MEMBER MESSAGING ----------------
    private fun pairConversationId(uidA: String, uidB: String): String {
        val sorted = listOf(uidA.trim(), uidB.trim()).sorted()
        return "${sorted[0]}_${sorted[1]}"
    }

    fun startMemberInboxListener() {
        val myUid = auth.currentUser?.uid
        if (myUid.isNullOrBlank()) return
        runCatching { inboxListener?.remove() }
        inboxListener = db.collection("conversations")
            .whereArrayContains("participants", myUid)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    val payload = JSONObject().apply {
                        put("ok", false)
                        put("error", AuthErrorFormatter.format(err))
                    }.toString()
                    activity.evaluateJs("window.onInboxConversationsUpdated?.($payload);")
                    return@addSnapshotListener
                }
                val docs = snap?.documents.orEmpty()
                    .sortedByDescending { it.getLong("lastAt") ?: it.getLong("updatedAt") ?: 0L }
                    .take(50)
                scope.launch {
                    refreshSupportDirectory(force = false)
                    val peerUids = docs.mapNotNull { doc ->
                        val parts = (doc.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                        parts.firstOrNull { it != myUid }
                    }.distinct()

                    val profiles = fetchUserSummaries(peerUids)
                    val arr = JSONArray()
                    docs.forEach { doc ->
                        val parts = (doc.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                        val peerUid = parts.firstOrNull { it != myUid }.orEmpty()
                        val peerInfo = profiles[peerUid]
                        val unreadMap = doc.get("unreadBy") as? Map<*, *>
                        val isUnread = unreadMap?.get(myUid) == true
                        val ts = doc.getLong("lastAt") ?: doc.getLong("updatedAt") ?: 0L
                        arr.put(JSONObject().apply {
                            put("convId", doc.id)
                            put("peerUid", peerUid)
                            put("peerName", peerInfo?.optString("displayName")?.ifBlank { null } ?: "عضو Theme Hub")
                            put("peerUsername", peerInfo?.optString("username") ?: "")
                            put("peerPhoto", peerInfo?.optString("photoURL") ?: "")
                            put("peerFrame", peerInfo?.optString("frame") ?: "")
                            put("peerIsAdmin", peerInfo?.optBoolean("isAdmin") ?: false)
                            put("lastMessage", doc.getString("lastMessage") ?: "")
                            put("lastAt", ts)
                            put("updatedAt", ts)
                            put("unread", isUnread)
                        })
                    }
                    val payload = JSONObject().apply {
                        put("ok", true)
                        put("conversations", arr)
                    }.toString()
                    activity.evaluateJs("window.onInboxConversationsUpdated?.($payload);")
                }
            }
    }

    fun stopMemberInboxListener() {
        runCatching { inboxListener?.remove() }
        inboxListener = null
    }

    fun startConversationChatListener(rawPeerUid: String) {
        val myUid = auth.currentUser?.uid ?: return
        stopConversationChatListener()

        scope.launch {
            refreshSupportDirectory(force = false)
            val peerUid = if (rawPeerUid.isBlank() || rawPeerUid == "__admin__") {
                refreshSupportDirectory(force = true)
                cachedOwnerUid.ifBlank { cachedAdminUids.firstOrNull().orEmpty() }
            } else {
                rawPeerUid.trim()
            }

            if (peerUid.isBlank() || peerUid == myUid) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", if (peerUid == myUid) "لا يمكنك مراسلة نفسك" else "لم يتم تفعيل حساب المشرف للاستقبال بعد")
                }.toString()
                activity.evaluateJs("window.onChatMessagesUpdated?.($payload);")
                return@launch
            }

            val peerProfile = fetchUserSummaries(listOf(peerUid))[peerUid]
            val personalBlockedByMe = runCatching {
                db.collection("users").document(myUid)
                    .collection("blockedUsers").document(peerUid)
                    .get().await().exists()
            }.getOrDefault(false)

            val convId = pairConversationId(myUid, peerUid)
            val convRef = db.collection("conversations").document(convId)

            chatConvListener = convRef.addSnapshotListener { convSnap, convErr ->
                if (convErr != null) {
                    val payload = JSONObject().apply {
                        put("ok", false)
                        put("convId", convId)
                        put("peerUid", peerUid)
                        put("error", AuthErrorFormatter.format(convErr))
                    }.toString()
                    activity.evaluateJs("window.onChatMessagesUpdated?.($payload);")
                    return@addSnapshotListener
                }

                if (convSnap == null || !convSnap.exists()) {
                    runCatching { chatMessagesListener?.remove() }
                    chatMessagesListener = null
                    val payload = JSONObject().apply {
                        put("ok", true)
                        put("convId", convId)
                        put("peerUid", peerUid)
                        put("peerName", peerProfile?.optString("displayName") ?: "عضو")
                        put("peerUsername", peerProfile?.optString("username") ?: "")
                        put("peerPhoto", peerProfile?.optString("photoURL") ?: "")
                        put("peerFrame", peerProfile?.optString("frame") ?: "")
                        put("peerIsAdmin", peerProfile?.optBoolean("isAdmin") ?: false)
                        put("personalBlockedByMe", personalBlockedByMe)
                        put("messages", JSONArray())
                    }.toString()
                    activity.evaluateJs("window.onChatMessagesUpdated?.($payload);")
                    return@addSnapshotListener
                }

                if (chatMessagesListener == null) {
                    chatMessagesListener = convRef.collection("messages")
                        .orderBy("createdAt", Query.Direction.ASCENDING)
                        .limitToLast(100)
                        .addSnapshotListener { msgSnap, msgErr ->
                            if (msgErr != null) {
                                val payload = JSONObject().apply {
                                    put("ok", false)
                                    put("convId", convId)
                                    put("peerUid", peerUid)
                                    put("error", AuthErrorFormatter.format(msgErr))
                                }.toString()
                                activity.evaluateJs("window.onChatMessagesUpdated?.($payload);")
                                return@addSnapshotListener
                            }

                            val arr = JSONArray()
                            val toMarkRead = mutableListOf<com.google.firebase.firestore.DocumentReference>()
                            msgSnap?.documents?.forEach { doc ->
                                val senderId = doc.getString("senderId") ?: ""
                                val isRead = doc.getBoolean("read") ?: false
                                arr.put(JSONObject().apply {
                                    put("id", doc.id)
                                    put("text", doc.getString("text") ?: "")
                                    put("senderId", senderId)
                                    put("createdAt", doc.getLong("createdAt") ?: 0L)
                                    put("read", isRead)
                                })
                                if (!isRead && senderId.isNotEmpty() && senderId != myUid) {
                                    toMarkRead.add(doc.reference)
                                }
                            }
                            if (toMarkRead.isNotEmpty()) {
                                runCatching {
                                    val batch = db.batch()
                                    toMarkRead.forEach { batch.update(it, "read", true) }
                                    batch.commit()
                                }
                            }
                            val curUnreadMap = convSnap.get("unreadBy") as? Map<*, *>
                            if (curUnreadMap?.get(myUid) == true) {
                                runCatching { convRef.update("unreadBy.$myUid", false) }
                            }

                            val payload = JSONObject().apply {
                                put("ok", true)
                                put("convId", convId)
                                put("peerUid", peerUid)
                                put("peerName", peerProfile?.optString("displayName") ?: "عضو")
                                put("peerUsername", peerProfile?.optString("username") ?: "")
                                put("peerPhoto", peerProfile?.optString("photoURL") ?: "")
                                put("peerFrame", peerProfile?.optString("frame") ?: "")
                                put("peerIsAdmin", peerProfile?.optBoolean("isAdmin") ?: false)
                                put("personalBlockedByMe", personalBlockedByMe)
                                put("messages", arr)
                            }.toString()
                            activity.evaluateJs("window.onChatMessagesUpdated?.($payload);")
                        }
                }
            }
        }
    }

    fun stopConversationChatListener() {
        runCatching { chatMessagesListener?.remove() }
        runCatching { chatConvListener?.remove() }
        chatMessagesListener = null
        chatConvListener = null
    }

    fun sendMessage(rawPeerUid: String, text: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onChatSendResult?.({ok:false,needAuth:true,error:'يجب تسجيل الدخول للمراسلة'});")
            return
        }
        if (activity.currentUserBlocked) {
            activity.evaluateJs("window.onChatSendResult?.({ok:false,error:'تم حظر حسابك من المراسلة'});")
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastMessageSentAtMs
        if (elapsed < 2000L) {
            val sec = (((2000L - elapsed) + 999) / 1000).coerceAtLeast(1)
            activity.evaluateJs("window.onChatSendResult?.({ok:false,rateLimited:true,error:'يرجى الانتظار $sec ثانية قبل إرسال رسالة أخرى'});")
            return
        }
        val cleanText = text.trim().take(1000)
        if (cleanText.isEmpty()) return

        scope.launch {
            try {
                refreshSupportDirectory(force = false)
                val peerUid = if (rawPeerUid.isBlank() || rawPeerUid == "__admin__") {
                    refreshSupportDirectory(force = true)
                    cachedOwnerUid.ifBlank { cachedAdminUids.firstOrNull().orEmpty() }
                } else {
                    rawPeerUid.trim()
                }
                if (peerUid.isBlank() || peerUid == user.uid) {
                    val msg = if (peerUid == user.uid) "لا يمكنك مراسلة نفسك" else "المستلم غير متوفر حالياً"
                    activity.evaluateJs("window.onChatSendResult?.({ok:false,error:${JSONObject.quote(msg)}});")
                    return@launch
                }

                lastMessageSentAtMs = System.currentTimeMillis()
                val sorted = listOf(user.uid, peerUid).sorted()
                val convId = "${sorted[0]}_${sorted[1]}"
                val convRef = db.collection("conversations").document(convId)

                val convMeta = hashMapOf<String, Any>(
                    "participants" to sorted,
                    "participantA" to sorted[0],
                    "participantB" to sorted[1],
                    "lastMessage" to cleanText,
                    "lastAt" to now,
                    "updatedAt" to now,
                    "unreadBy" to hashMapOf(
                        user.uid to false,
                        peerUid to true
                    )
                )
                val existingConv = convRef.get().await()
                if (!existingConv.exists()) {
                    convMeta["hasReportedMessage"] = false
                    convRef.set(convMeta).await()
                } else {
                    val existingParts = (existingConv.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    val hasValidMeta = existingParts.containsAll(sorted) &&
                        !existingConv.getString("participantA").isNullOrBlank() &&
                        !existingConv.getString("participantB").isNullOrBlank()
                    if (!hasValidMeta) {
                        convRef.set(convMeta, SetOptions.merge()).await()
                    }
                }

                val msgData = hashMapOf<String, Any>(
                    "text" to cleanText,
                    "senderId" to user.uid,
                    "createdAt" to now,
                    "read" to false
                )
                convRef.collection("messages").add(msgData).await()
                convRef.set(convMeta, SetOptions.merge()).await()
                activity.evaluateJs("window.onChatSendResult?.({ok:true});")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onChatSendResult?.($errJson);")
            }
        }
    }

    // ---------------- COMMENTS, LIKES & 1-LEVEL REPLIES ----------------
    private suspend fun fetchUserSummaries(uids: List<String>): Map<String, JSONObject> {
        val result = mutableMapOf<String, JSONObject>()
        val unique = uids.filter { it.isNotBlank() }.distinct()
        refreshSupportDirectory(force = false)
        for (uid in unique) {
            runCatching {
                val snap = db.collection("users").document(uid).get().await()
                if (snap.exists()) {
                    result[uid] = JSONObject().apply {
                        put("displayName", snap.getString("displayName") ?: "مستخدم Theme Hub")
                        put("username", snap.getString("username") ?: "")
                        put("photoURL", snap.getString("photoURL") ?: "")
                        put("blocked", snap.getBoolean("blocked") ?: false)
                        put("publisher", snap.getBoolean("publisher") ?: false)
                        put("badges", JSONArray(extractStringList(snap, "badges")))
                        put("frame", snap.getString("frame") ?: "")
                        put("customTitle", snap.getString("customTitle") ?: "")
                        put("isAdmin", cachedAdminUids.contains(uid))
                    }
                }
            }
        }
        return result
    }

    fun fetchThemeCommentsPage(themeId: String, beforeCreatedAt: Long) {
        scope.launch {
            try {
                var query: Query = db.collection("themes").document(themeId)
                    .collection("comments")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                if (beforeCreatedAt > 0L) {
                    query = query.whereLessThan("createdAt", beforeCreatedAt)
                }
                val snap = query.limit(21).get().await()
                val docs = snap.documents
                val hasMore = docs.size > 20
                val slice = if (hasMore) docs.take(20) else docs

                val authorUids = slice.mapNotNull { it.getString("uid") }
                val authorMap = fetchUserSummaries(authorUids)
                val myUid = auth.currentUser?.uid

                val arr = JSONArray()
                for (d in slice) {
                    val uid = d.getString("uid") ?: ""
                    val author = authorMap[uid]
                    val likedByMe = if (!myUid.isNullOrBlank()) {
                        runCatching {
                            d.reference.collection("likes").document(myUid).get().await().exists()
                        }.getOrDefault(false)
                    } else false

                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("uid", uid)
                        put("name", author?.optString("displayName")?.ifBlank { null } ?: (d.getString("name") ?: "مستخدم"))
                        put("username", author?.optString("username") ?: "")
                        put("photo", author?.optString("photoURL") ?: (d.getString("photo") ?: ""))
                        put("frame", author?.optString("frame") ?: "")
                        put("badges", author?.optJSONArray("badges") ?: JSONArray())
                        put("customTitle", author?.optString("customTitle") ?: "")
                        put("isAdmin", author?.optBoolean("isAdmin") ?: false)
                        put("isPublisher", author?.optBoolean("publisher") ?: false)
                        put("pinned", d.getBoolean("pinned") ?: false)
                        put("text", d.getString("text") ?: "")
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                        put("likes", d.getLong("likes")?.toInt() ?: 0)
                        put("replyCount", d.getLong("replyCount")?.toInt() ?: 0)
                        put("likedByMe", likedByMe)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("append", beforeCreatedAt > 0L)
                    put("hasMore", hasMore)
                    put("comments", arr)
                }.toString()
                activity.evaluateJs("window.onThemeCommentsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("themeId", themeId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onThemeCommentsLoaded?.($payload);")
            }
        }
    }

    fun addComment(themeId: String, text: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onCommentAddedError?.({ok:false,needAuth:true,error:'يجب تسجيل الدخول للتعليق'});")
            return
        }
        if (activity.currentUserBlocked) {
            activity.evaluateJs("window.onCommentAddedError?.({ok:false,error:'تم حظر حسابك من التعليق'});")
            return
        }
        val cleanText = text.trim().take(500)
        if (cleanText.isEmpty()) return

        scope.launch {
            try {
                val userMap = fetchUserSummaries(listOf(user.uid))[user.uid]
                val displayName = userMap?.optString("displayName")?.ifBlank { null }
                    ?: (user.displayName ?: "مستخدم Theme Hub").take(60)
                val username = userMap?.optString("username").orEmpty()
                val photo = userMap?.optString("photoURL") ?: (user.photoUrl?.toString() ?: "")
                val now = System.currentTimeMillis()

                val commentData = hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "name" to displayName,
                    "photo" to photo,
                    "text" to cleanText,
                    "createdAt" to now,
                    "likes" to 0,
                    "replyCount" to 0,
                    "pinned" to false
                )

                val docRef = db.collection("themes").document(themeId)
                    .collection("comments").add(commentData).await()

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("id", docRef.id)
                    put("uid", user.uid)
                    put("name", displayName)
                    put("username", username)
                    put("photo", photo)
                    put("frame", userMap?.optString("frame") ?: "")
                    put("badges", userMap?.optJSONArray("badges") ?: JSONArray())
                    put("customTitle", userMap?.optString("customTitle") ?: "")
                    put("isAdmin", activity.currentUserIsAdmin)
                    put("isPublisher", userMap?.optBoolean("publisher") ?: false)
                    put("pinned", false)
                    put("text", cleanText)
                    put("createdAt", now)
                    put("likes", 0)
                    put("replyCount", 0)
                    put("likedByMe", false)
                }.toString()
                activity.evaluateJs("window.onCommentAddedSuccess?.($payload);")
                activity.sendToastToJs("تم إضافة التعليق")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onCommentAddedError?.($errJson);")
            }
        }
    }

    fun deleteComment(themeId: String, commentId: String) {
        scope.launch {
            try {
                db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                    .delete().await()
                if (activity.currentUserIsAdmin) {
                    writeAuditLog("delete_comment", "comment", "$themeId/$commentId", "حذف تعليق")
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("commentId", commentId)
                }.toString()
                activity.evaluateJs("window.onCommentDeleted?.($payload);")
                activity.sendToastToJs("تم حذف التعليق")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل حذف التعليق: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun toggleCommentLike(themeId: String, commentId: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onCommentAddedError?.({ok:false,needAuth:true,error:'يجب تسجيل الدخول للإعجاب'});")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        scope.launch {
            try {
                val commentRef = db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                val likeRef = commentRef.collection("likes").document(user.uid)

                val (liked, newCount) = db.runTransaction { tx ->
                    val commentSnap = tx.get(commentRef)
                    val likeSnap = tx.get(likeRef)
                    val cur = (commentSnap.getLong("likes")?.toInt() ?: 0).coerceAtLeast(0)
                    if (likeSnap.exists()) {
                        val next = (cur - 1).coerceAtLeast(0)
                        tx.delete(likeRef)
                        tx.update(commentRef, "likes", next)
                        false to next
                    } else {
                        val next = cur + 1
                        tx.set(likeRef, hashMapOf("uid" to user.uid, "createdAt" to System.currentTimeMillis()))
                        tx.update(commentRef, "likes", next)
                        true to next
                    }
                }.await()

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("liked", liked)
                    put("likes", newCount)
                }.toString()
                activity.evaluateJs("window.onCommentLikeToggled?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر تحديث الإعجاب: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun fetchCommentReplies(themeId: String, commentId: String) {
        scope.launch {
            try {
                val snap = db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                    .collection("replies")
                    .orderBy("createdAt", Query.Direction.ASCENDING)
                    .limit(50)
                    .get().await()

                val authorMap = fetchUserSummaries(snap.documents.mapNotNull { it.getString("uid") })
                val myUid = auth.currentUser?.uid
                val arr = JSONArray()
                for (d in snap.documents) {
                    val uid = d.getString("uid") ?: ""
                    val author = authorMap[uid]
                    val likedByMe = if (!myUid.isNullOrBlank()) {
                        runCatching {
                            d.reference.collection("likes").document(myUid).get().await().exists()
                        }.getOrDefault(false)
                    } else false

                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("uid", uid)
                        put("name", author?.optString("displayName")?.ifBlank { null } ?: "مستخدم")
                        put("username", author?.optString("username") ?: "")
                        put("photo", author?.optString("photoURL") ?: "")
                        put("frame", author?.optString("frame") ?: "")
                        put("badges", author?.optJSONArray("badges") ?: JSONArray())
                        put("isAdmin", author?.optBoolean("isAdmin") ?: false)
                        put("isPublisher", author?.optBoolean("publisher") ?: false)
                        put("text", d.getString("text") ?: "")
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                        put("likes", d.getLong("likes")?.toInt() ?: 0)
                        put("likedByMe", likedByMe)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("replies", arr)
                }.toString()
                activity.evaluateJs("window.onCommentRepliesLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onCommentRepliesLoaded?.($payload);")
            }
        }
    }

    fun addCommentReply(themeId: String, commentId: String, text: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onCommentAddedError?.({ok:false,needAuth:true,error:'يجب تسجيل الدخول للرد'});")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("تم حظر حسابك من التعليق")
            return
        }
        val cleanText = text.trim().take(500)
        if (cleanText.isEmpty()) return

        scope.launch {
            try {
                val commentRef = db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                val replyRef = commentRef.collection("replies").document()
                val now = System.currentTimeMillis()
                val userMap = fetchUserSummaries(listOf(user.uid))[user.uid]

                val newCount = db.runTransaction { tx ->
                    val commentSnap = tx.get(commentRef)
                    val nextCount = (commentSnap.getLong("replyCount")?.toInt() ?: 0) + 1
                    tx.set(
                        replyRef,
                        hashMapOf(
                            "uid" to user.uid,
                            "text" to cleanText,
                            "createdAt" to now,
                            "likes" to 0
                        )
                    )
                    tx.update(commentRef, "replyCount", nextCount)
                    nextCount
                }.await()

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("replyCount", newCount)
                    put("reply", JSONObject().apply {
                        put("id", replyRef.id)
                        put("uid", user.uid)
                        put("name", userMap?.optString("displayName")?.ifBlank { null } ?: (user.displayName ?: "مستخدم"))
                        put("username", userMap?.optString("username") ?: "")
                        put("photo", userMap?.optString("photoURL") ?: (user.photoUrl?.toString() ?: ""))
                        put("frame", userMap?.optString("frame") ?: "")
                        put("badges", userMap?.optJSONArray("badges") ?: JSONArray())
                        put("isAdmin", activity.currentUserIsAdmin)
                        put("isPublisher", userMap?.optBoolean("publisher") ?: false)
                        put("text", cleanText)
                        put("createdAt", now)
                        put("likes", 0)
                        put("likedByMe", false)
                    })
                }.toString()
                activity.evaluateJs("window.onCommentReplyAdded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onCommentReplyAdded?.($err);")
            }
        }
    }

    fun deleteCommentReply(themeId: String, commentId: String, replyId: String) {
        scope.launch {
            try {
                val commentRef = db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                val replyRef = commentRef.collection("replies").document(replyId)

                val newCount = db.runTransaction { tx ->
                    val commentSnap = tx.get(commentRef)
                    val cur = (commentSnap.getLong("replyCount")?.toInt() ?: 0).coerceAtLeast(0)
                    val next = (cur - 1).coerceAtLeast(0)
                    tx.delete(replyRef)
                    tx.update(commentRef, "replyCount", next)
                    next
                }.await()

                if (activity.currentUserIsAdmin) {
                    writeAuditLog("delete_reply", "reply", "$themeId/$commentId/$replyId", "حذف رد")
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("replyId", replyId)
                    put("replyCount", newCount)
                }.toString()
                activity.evaluateJs("window.onCommentReplyDeleted?.($payload);")
                activity.sendToastToJs("تم حذف الرد")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل حذف الرد: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun toggleReplyLike(themeId: String, commentId: String, replyId: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onCommentAddedError?.({ok:false,needAuth:true,error:'يجب تسجيل الدخول للإعجاب'});")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        scope.launch {
            try {
                val replyRef = db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                    .collection("replies").document(replyId)
                val likeRef = replyRef.collection("likes").document(user.uid)

                val (liked, newCount) = db.runTransaction { tx ->
                    val replySnap = tx.get(replyRef)
                    val likeSnap = tx.get(likeRef)
                    val cur = (replySnap.getLong("likes")?.toInt() ?: 0).coerceAtLeast(0)
                    if (likeSnap.exists()) {
                        val next = (cur - 1).coerceAtLeast(0)
                        tx.delete(likeRef)
                        tx.update(replyRef, "likes", next)
                        false to next
                    } else {
                        val next = cur + 1
                        tx.set(likeRef, hashMapOf("uid" to user.uid, "createdAt" to System.currentTimeMillis()))
                        tx.update(replyRef, "likes", next)
                        true to next
                    }
                }.await()

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("commentId", commentId)
                    put("replyId", replyId)
                    put("liked", liked)
                    put("likes", newCount)
                }.toString()
                activity.evaluateJs("window.onReplyLikeToggled?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر تحديث الإعجاب: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    // ---------------- ABUSE REPORTS (WITH LAST 10 MESSAGES FOR CONVERSATIONS) ----------------
    fun submitReport(targetType: String, targetId: String, parentId: String, reason: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.sendToastToJs("يجب تسجيل الدخول للإبلاغ")
            return
        }
        scope.launch {
            try {
                val data = hashMapOf<String, Any>(
                    "reporterUid" to user.uid,
                    "targetType" to targetType.take(40),
                    "targetId" to targetId.take(120),
                    "parentId" to parentId.take(120),
                    "reason" to reason.trim().take(300),
                    "status" to "open",
                    "createdAt" to System.currentTimeMillis()
                )

                if ((targetType == "message" || targetType == "conversation") && parentId.isNotBlank()) {
                    val convRef = db.collection("conversations").document(parentId)
                    val recentSnap = runCatching {
                        convRef.collection("messages")
                            .orderBy("createdAt", Query.Direction.DESCENDING)
                            .limit(10)
                            .get().await()
                    }.getOrNull()

                    val recentList = recentSnap?.documents?.reversed()?.map { d ->
                        hashMapOf(
                            "senderId" to (d.getString("senderId") ?: ""),
                            "text" to (d.getString("text") ?: "").take(250),
                            "createdAt" to (d.getLong("createdAt") ?: 0L)
                        )
                    }.orEmpty()
                    data["recentMessages"] = recentList
                    runCatching { convRef.update("hasReportedMessage", true).await() }
                }

                db.collection("reports").add(data).await()
                activity.sendToastToJs("✓ تم إرسال البلاغ للمراجعة")
                activity.evaluateJs("window.onReportSubmitted?.({ok:true});")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر إرسال البلاغ: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun fetchReportsForAdmin() {
        if (!activity.currentUserIsAdmin) {
            val payload = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح: عرض البلاغات متاح للمشرفين فقط")
            }.toString()
            activity.evaluateJs("window.onAdminReportsLoaded?.($payload);")
            return
        }
        scope.launch {
            try {
                val snap = db.collection("reports")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(50)
                    .get().await()
                val arr = JSONArray()
                snap.documents.forEach { d ->
                    val recent = JSONArray()
                    val rawRecent = d.get("recentMessages") as? List<*>
                    rawRecent?.forEach { entry ->
                        val map = entry as? Map<*, *> ?: return@forEach
                        recent.put(JSONObject().apply {
                            put("senderId", map["senderId"] ?: "")
                            put("text", map["text"] ?: "")
                            put("createdAt", (map["createdAt"] as? Number)?.toLong() ?: 0L)
                        })
                    }
                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("reporterUid", d.getString("reporterUid") ?: "")
                        put("targetType", d.getString("targetType") ?: "")
                        put("targetId", d.getString("targetId") ?: "")
                        put("parentId", d.getString("parentId") ?: "")
                        put("reason", d.getString("reason") ?: "")
                        put("status", d.getString("status") ?: "open")
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                        put("recentMessages", recent)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("reports", arr)
                }.toString()
                activity.evaluateJs("window.onAdminReportsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminReportsLoaded?.($payload);")
            }
        }
    }

    fun resolveReport(reportId: String, status: String) {
        if (!activity.currentUserIsAdmin) return
        scope.launch {
            try {
                db.collection("reports").document(reportId)
                    .update("status", status, "resolvedAt", System.currentTimeMillis())
                    .await()
                writeAuditLog("resolve_report", "report", reportId, "status=$status")
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("id", reportId)
                    put("status", status)
                }.toString()
                activity.evaluateJs("window.onReportResolved?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل تحديث البلاغ: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    // ---------------- OPEN THEME SUBMISSIONS (ANY AUTHENTICATED USER) ----------------
    fun submitThemeSubmission(jsonPayload: String) {
        val user = auth.currentUser
        if (user == null) {
            emitSubmissionSaveResult(false, "يجب تسجيل الدخول أولاً")
            return
        }
        if (activity.currentUserBlocked) {
            emitSubmissionSaveResult(false, "حسابك محظور من إرسال الثيمات")
            return
        }
        scope.launch {
            try {
                val req = JSONObject(jsonPayload)
                val existingId = req.optString("id", "").trim()
                val title = req.optString("title", "").trim().take(100)
                val category = req.optString("category", "داكن").trim()
                val version = req.optString("version", "v1.0").trim().ifBlank { "v1.0" }
                val descAr = req.optString("description_ar", "").trim().take(1200)
                val descEn = req.optString("description_en", "").trim().take(1200)
                val variantsJson = req.optString("variantsJson", "[]")
                val previewsJson = req.optString("previewsJson", "[]")

                if (title.isEmpty()) {
                    emitSubmissionSaveResult(false, "يرجى إدخال اسم الثيم")
                    return@launch
                }
                if (variantsJson.length + previewsJson.length > 780_000) {
                    emitSubmissionSaveResult(false, "حجم صور المعاينة كبير جداً بعد الضغط، يرجى تقليل عدد الصور أو استخدام روابط خارجية.")
                    return@launch
                }

                val userDoc = db.collection("users").document(user.uid).get().await()

                // Max 3 concurrent pending submissions per non-admin user (simple single-field query)
                if (existingId.isEmpty() && !activity.currentUserIsAdmin) {
                    val myExistingSnap = db.collection("submissions")
                        .whereEqualTo("publisherUid", user.uid)
                        .get().await()
                    val pendingCount = myExistingSnap.documents.count { it.getString("status") == "pending" }
                    if (pendingCount >= 3) {
                        emitSubmissionSaveResult(false, "لديك بالفعل 3 طلبات قيد المراجعة. انتظر مراجعة المشرف قبل إرسال طلب جديد.")
                        return@launch
                    }
                }

                val now = System.currentTimeMillis()
                val publisherName = userDoc.getString("displayName")?.ifBlank { null }
                    ?: (user.displayName ?: "عضو Theme Hub")
                val publisherUsername = userDoc.getString("username").orEmpty()

                val data = hashMapOf<String, Any>(
                    "publisherUid" to user.uid,
                    "publisherName" to publisherName,
                    "publisherUsername" to publisherUsername,
                    "title" to title,
                    "category" to category,
                    "version" to version,
                    "description_ar" to descAr,
                    "description_en" to descEn,
                    "variantsJson" to variantsJson,
                    "previewsJson" to previewsJson,
                    "status" to "pending",
                    "rejectReason" to "",
                    "updatedAt" to now
                )

                val docId = if (existingId.isNotEmpty()) {
                    db.collection("submissions").document(existingId).set(data, SetOptions.merge()).await()
                    existingId
                } else {
                    data["createdAt"] = now
                    val added = db.collection("submissions").add(data).await()
                    added.id
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("id", docId)
                }.toString()
                activity.evaluateJs("window.onSubmissionSaved?.($payload);")
                activity.sendToastToJs("✓ تم إرسال طلبك، سيراجعه المشرفون وسنخبرك بالنتيجة")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitSubmissionSaveResult(false, AuthErrorFormatter.format(e))
            }
        }
    }

    private fun emitSubmissionSaveResult(ok: Boolean, error: String) {
        val payload = JSONObject().apply {
            put("ok", ok)
            if (!ok) put("error", error)
        }.toString()
        activity.evaluateJs("window.onSubmissionSaved?.($payload);")
    }

    fun fetchMySubmissions() {
        val user = auth.currentUser
        if (user == null) {
            val payload = JSONObject().apply {
                put("ok", false)
                put("error", "يرجى تسجيل الدخول أولاً")
            }.toString()
            activity.evaluateJs("window.onMySubmissionsLoaded?.($payload);")
            return
        }
        scope.launch {
            try {
                val snap = db.collection("submissions")
                    .whereEqualTo("publisherUid", user.uid)
                    .limit(100)
                    .get().await()
                val sortedDocs = snap.documents
                    .sortedByDescending { it.getLong("createdAt") ?: 0L }
                    .take(30)
                val arr = serializeSubmissions(sortedDocs)
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("submissions", arr)
                }.toString()
                activity.evaluateJs("window.onMySubmissionsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onMySubmissionsLoaded?.($payload);")
            }
        }
    }

    fun fetchAllSubmissionsForAdmin() {
        if (!activity.currentUserIsAdmin) {
            val payload = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح: مراجعة الطلبات متاحة للمشرفين فقط")
            }.toString()
            activity.evaluateJs("window.onAdminSubmissionsLoaded?.($payload);")
            return
        }
        scope.launch {
            try {
                val snap = db.collection("submissions")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(60)
                    .get().await()
                val arr = serializeSubmissions(snap.documents)
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("submissions", arr)
                }.toString()
                activity.evaluateJs("window.onAdminSubmissionsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminSubmissionsLoaded?.($payload);")
            }
        }
    }

    fun updateSubmissionStatus(
        submissionId: String,
        status: String,
        rejectReason: String,
        approvedThemeId: String
    ) {
        if (!activity.currentUserIsAdmin) {
            activity.sendToastToJs("غير مصرح: إجراء المراجعة متاح للمشرفين فقط")
            return
        }
        val reviewerUid = auth.currentUser?.uid.orEmpty()
        scope.launch {
            try {
                val subRef = db.collection("submissions").document(submissionId)
                val now = System.currentTimeMillis()
                val cleanReason = rejectReason.trim().take(300)
                val cleanThemeId = approvedThemeId.trim()

                val subInfo = db.runTransaction { tx ->
                    val snap = tx.get(subRef)
                    if (!snap.exists()) throw IllegalStateException("الطلب غير موجود")
                    val curStatus = snap.getString("status") ?: "pending"
                    if (status == "approved" && curStatus == "approved") {
                        throw IllegalStateException("تمت الموافقة على هذا الطلب ونشره مسبقاً")
                    }
                    val updates = hashMapOf<String, Any>(
                        "status" to status,
                        "rejectReason" to cleanReason,
                        "approvedThemeId" to cleanThemeId,
                        "reviewedByUid" to reviewerUid,
                        "reviewedAt" to now,
                        "updatedAt" to now
                    )
                    tx.update(subRef, updates)
                    listOf(
                        snap.getString("publisherUid") ?: "",
                        snap.getString("publisherName") ?: "",
                        snap.getString("publisherUsername") ?: "",
                        snap.getString("title") ?: "ثيم"
                    )
                }.await()
                val publisherUid = subInfo[0]
                val publisherName = subInfo[1]
                val publisherUsername = subInfo[2]
                val subTitle = subInfo[3]

                if (status == "approved") {
                    if (cleanThemeId.isNotEmpty() && publisherUid.isNotEmpty()) {
                        runCatching {
                            db.collection("themes").document(cleanThemeId).set(
                                hashMapOf<String, Any>(
                                    "publisherUid" to publisherUid,
                                    "publisherName" to publisherName,
                                    "publisherUsername" to publisherUsername,
                                    "updatedAt" to now
                                ),
                                SetOptions.merge()
                            ).await()
                        }
                    }
                    sendUserNotification(
                        targetUid = publisherUid,
                        type = "submission_approved",
                        title = "🎉 تم قبول ونشر ثيمك",
                        body = "تهانينا! تمت الموافقة على ثيم «$subTitle» ونشره في المتجر.",
                        submissionId = submissionId,
                        themeId = cleanThemeId
                    )
                    writeAuditLog("approve_submission", "submission", submissionId, "ثيم: $subTitle ($cleanThemeId)")
                } else if (status == "rejected") {
                    sendUserNotification(
                        targetUid = publisherUid,
                        type = "submission_rejected",
                        title = "تمت مراجعة طلب نشر ثيمك",
                        body = if (cleanReason.isNotEmpty()) "تم رفض «$subTitle». السبب: $cleanReason" else "تم رفض طلب «$subTitle».",
                        submissionId = submissionId,
                        themeId = ""
                    )
                    writeAuditLog("reject_submission", "submission", submissionId, "السبب: $cleanReason")
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("id", submissionId)
                    put("status", status)
                }.toString()
                activity.evaluateJs("window.onSubmissionStatusUpdated?.($payload);")
                fetchAllSubmissionsForAdmin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = if (e is IllegalStateException) (e.message ?: "خطأ") else AuthErrorFormatter.format(e)
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("id", submissionId)
                    put("error", msg)
                }.toString()
                activity.evaluateJs("window.onSubmissionStatusUpdated?.($payload);")
            }
        }
    }

    private fun serializeSubmissions(docs: List<DocumentSnapshot>): JSONArray {
        val arr = JSONArray()
        docs.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("publisherUid", d.getString("publisherUid") ?: "")
                put("publisherName", d.getString("publisherName") ?: "عضو")
                put("publisherUsername", d.getString("publisherUsername") ?: "")
                put("title", d.getString("title") ?: "")
                put("category", d.getString("category") ?: "داكن")
                put("version", d.getString("version") ?: "v1.0")
                put("description_ar", d.getString("description_ar") ?: "")
                put("description_en", d.getString("description_en") ?: "")
                put("variantsJson", d.getString("variantsJson") ?: "[]")
                put("previewsJson", d.getString("previewsJson") ?: "[]")
                put("status", d.getString("status") ?: "pending")
                put("rejectReason", d.getString("rejectReason") ?: "")
                put("approvedThemeId", d.getString("approvedThemeId") ?: "")
                put("createdAt", d.getLong("createdAt") ?: 0L)
                put("updatedAt", d.getLong("updatedAt") ?: 0L)
            })
        }
        return arr
    }

    // ---------------- ADMIN MODERATION: USERS, CONTENT, STATS & AUDIT LOG ----------------
    fun adminListUsers(rawQuery: String) {
        if (!activity.currentUserIsAdmin) {
            val payload = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح: إدارة المستخدمين متاحة للمشرفين فقط")
            }.toString()
            activity.evaluateJs("window.onAdminUsersLoaded?.($payload);")
            return
        }
        scope.launch {
            try {
                refreshSupportDirectory(force = true)
                val q = rawQuery.trim().removePrefix("@").lowercase()
                val snap = if (q.isNotEmpty() && !rawQuery.contains("@") || (rawQuery.startsWith("@") && q.isNotEmpty())) {
                    val cleanPrefix = q.replace(Regex("[^a-z0-9_]"), "")
                    if (cleanPrefix.isNotEmpty()) {
                        db.collection("users")
                            .orderBy("usernameLower")
                            .startAt(cleanPrefix)
                            .endAt(cleanPrefix + "\uf8ff")
                            .limit(40)
                            .get().await()
                    } else {
                        db.collection("users").limit(40).get().await()
                    }
                } else {
                    db.collection("users").limit(40).get().await()
                }
                val arr = JSONArray()
                snap.documents
                    .filter { doc ->
                        if (q.isEmpty()) true
                        else {
                            val dn = (doc.getString("displayName") ?: "").lowercase()
                            val un = (doc.getString("username") ?: "").lowercase()
                            dn.contains(q) || un.contains(q) || doc.id.lowercase().contains(q)
                        }
                    }
                    .sortedByDescending { it.getLong("createdAt") ?: 0L }
                    .forEach { d ->
                        val uid = d.getString("uid") ?: d.id
                        arr.put(JSONObject().apply {
                            put("uid", uid)
                            put("displayName", d.getString("displayName") ?: "مستخدم")
                            put("username", d.getString("username") ?: "")
                            put("photoURL", d.getString("photoURL") ?: "")
                            put("bannerURL", d.getString("bannerURL") ?: "")
                            put("bio", d.getString("bio") ?: "")
                            put("blocked", d.getBoolean("blocked") ?: false)
                            put("publisher", d.getBoolean("publisher") ?: false)
                            put("badges", JSONArray(extractStringList(d, "badges")))
                            put("frame", d.getString("frame") ?: "")
                            put("customTitle", d.getString("customTitle") ?: "")
                            put("isAdmin", cachedAdminUids.contains(uid))
                            put("createdAt", d.getLong("createdAt") ?: 0L)
                        })
                    }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("users", arr)
                }.toString()
                activity.evaluateJs("window.onAdminUsersLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminUsersLoaded?.($payload);")
            }
        }
    }

    fun adminModerateUser(targetUid: String, action: String, payloadJson: String) {
        if (!activity.currentUserIsAdmin || targetUid.isBlank()) return
        scope.launch {
            try {
                val data = runCatching { JSONObject(payloadJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                val userRef = db.collection("users").document(targetUid)
                val now = System.currentTimeMillis()
                when (action) {
                    "set_badges" -> {
                        val arr = data.optJSONArray("badges") ?: JSONArray()
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val b = arr.optString(i, "").trim()
                            if (b in ALLOWED_BADGES && b !in list) list.add(b)
                        }
                        userRef.update("badges", list, "updatedAt", now).await()
                        writeAuditLog("set_badges", "user", targetUid, "badges=${list.joinToString(",")}")
                    }
                    "set_frame" -> {
                        val frame = data.optString("frame", "").trim()
                        val safeFrame = if (frame in ALLOWED_FRAMES) frame else ""
                        userRef.update("frame", safeFrame, "updatedAt", now).await()
                        writeAuditLog("set_frame", "user", targetUid, "frame=$safeFrame")
                    }
                    "set_title" -> {
                        val customTitle = data.optString("customTitle", "").trim().take(30)
                        userRef.update("customTitle", customTitle, "updatedAt", now).await()
                        writeAuditLog("set_title", "user", targetUid, "title=$customTitle")
                    }
                    "edit_profile" -> {
                        val newName = data.optString("displayName", "").trim().take(60)
                        val newBio = data.optString("bio", "").trim().take(150)
                        val updates = hashMapOf<String, Any>("updatedAt" to now, "bio" to newBio)
                        if (newName.isNotEmpty()) updates["displayName"] = newName
                        userRef.update(updates).await()
                        writeAuditLog("admin_edit_profile", "user", targetUid, "name=$newName")
                    }
                    "clear_avatar" -> {
                        userRef.update("photoURL", "", "updatedAt", now).await()
                        writeAuditLog("clear_avatar", "user", targetUid, "تمت إزالة الصورة الشخصية")
                    }
                    "clear_banner" -> {
                        userRef.update("bannerURL", "", "updatedAt", now).await()
                        writeAuditLog("clear_banner", "user", targetUid, "تمت إزالة صورة الغلاف")
                    }
                    "reset_username" -> {
                        val snap = userRef.get().await()
                        val oldUname = (snap.getString("username") ?: "").trim().lowercase()
                        if (oldUname.isNotEmpty()) {
                            runCatching { db.collection("usernames").document(oldUname).delete().await() }
                        }
                        userRef.update(
                            mapOf(
                                "username" to "",
                                "usernameLower" to "",
                                "usernameChosen" to false,
                                "updatedAt" to now
                            )
                        ).await()
                        writeAuditLog("reset_username", "user", targetUid, "old=@$oldUname")
                    }
                    "delete_account" -> {
                        val snap = userRef.get().await()
                        val oldUname = (snap.getString("username") ?: "").trim().lowercase()
                        if (oldUname.isNotEmpty()) {
                            runCatching { db.collection("usernames").document(oldUname).delete().await() }
                        }
                        userRef.delete().await()
                        writeAuditLog("delete_user", "user", targetUid, "old=@$oldUname")
                    }
                }
                val res = JSONObject().apply {
                    put("ok", true)
                    put("uid", targetUid)
                    put("action", action)
                }.toString()
                activity.evaluateJs("window.onAdminUserModerated?.($res);")
                activity.sendToastToJs("✓ تم تنفيذ الإجراء وحفظه في السجل")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val res = JSONObject().apply {
                    put("ok", false)
                    put("uid", targetUid)
                    put("action", action)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminUserModerated?.($res);")
                activity.sendToastToJs("فشل الإجراء: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun adminModerateTheme(themeId: String, action: String, payloadJson: String) {
        if (!activity.currentUserIsAdmin || themeId.isBlank()) return
        scope.launch {
            try {
                val data = runCatching { JSONObject(payloadJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                val ref = db.collection("themes").document(themeId)
                val updates = hashMapOf<String, Any>("updatedAt" to System.currentTimeMillis())
                when (action) {
                    "set_pin" -> {
                        val pinned = data.optBoolean("pinned", false)
                        updates["pinned"] = pinned
                        ref.set(updates, SetOptions.merge()).await()
                        writeAuditLog(if (pinned) "pin_theme" else "unpin_theme", "theme", themeId, "pinned=$pinned")
                    }
                    "set_hide" -> {
                        val hidden = data.optBoolean("hidden", false)
                        updates["hidden"] = hidden
                        ref.set(updates, SetOptions.merge()).await()
                        writeAuditLog(if (hidden) "hide_theme" else "unhide_theme", "theme", themeId, "hidden=$hidden")
                    }
                    "set_override" -> {
                        val titleOverride = data.optString("titleOverride", "").trim().take(100)
                        val categoryOverride = data.optString("categoryOverride", "").trim().take(40)
                        updates["titleOverride"] = titleOverride
                        updates["categoryOverride"] = categoryOverride
                        ref.set(updates, SetOptions.merge()).await()
                        writeAuditLog("override_theme", "theme", themeId, "title=$titleOverride cat=$categoryOverride")
                    }
                }
                val res = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("action", action)
                }.toString()
                activity.evaluateJs("window.onAdminThemeModerated?.($res);")
                activity.fetchAllThemeRatingsSummary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val res = JSONObject().apply {
                    put("ok", false)
                    put("themeId", themeId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminThemeModerated?.($res);")
            }
        }
    }

    fun adminFetchRecentComments() {
        if (!activity.currentUserIsAdmin) return
        scope.launch {
            try {
                val commentDocs: List<DocumentSnapshot> = try {
                    db.collectionGroup("comments")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(30)
                        .get().await().documents
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ce: Throwable) {
                    val isPrecond = (ce as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
                    if (!isPrecond) throw ce
                    val themeDocs = db.collection("themes").limit(40).get().await()
                    val collected = mutableListOf<DocumentSnapshot>()
                    for (td in themeDocs.documents) {
                        val sub = runCatching {
                            td.reference.collection("comments")
                                .orderBy("createdAt", Query.Direction.DESCENDING)
                                .limit(10)
                                .get().await().documents
                        }.getOrDefault(emptyList())
                        collected.addAll(sub)
                    }
                    collected.sortedByDescending { it.getLong("createdAt") ?: 0L }.take(30)
                }
                val arr = JSONArray()
                commentDocs.forEach { d ->
                    val themeId = d.reference.parent.parent?.id ?: ""
                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("themeId", themeId)
                        put("uid", d.getString("uid") ?: "")
                        put("name", d.getString("name") ?: "مستخدم")
                        put("text", d.getString("text") ?: "")
                        put("pinned", d.getBoolean("pinned") ?: false)
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("comments", arr)
                }.toString()
                activity.evaluateJs("window.onAdminRecentCommentsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminRecentCommentsLoaded?.($payload);")
            }
        }
    }

    fun adminPinComment(themeId: String, commentId: String, pinned: Boolean) {
        if (!activity.currentUserIsAdmin || themeId.isBlank() || commentId.isBlank()) return
        scope.launch {
            try {
                db.collection("themes").document(themeId)
                    .collection("comments").document(commentId)
                    .update("pinned", pinned).await()
                writeAuditLog(if (pinned) "pin_comment" else "unpin_comment", "comment", "$themeId/$commentId", "pinned=$pinned")
                activity.sendToastToJs(if (pinned) "📌 تم تثبيت التعليق" else "تم إلغاء تثبيت التعليق")
                adminFetchRecentComments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل تحديث تثبيت التعليق: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun adminFetchStatsAndAuditLog() {
        if (!activity.currentUserIsAdmin) {
            val payload = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح")
            }.toString()
            activity.evaluateJs("window.onAdminStatsLoaded?.($payload);")
            return
        }
        scope.launch {
            try {
                val usersCount = runCatching {
                    db.collection("users").count().get(AggregateSource.SERVER).await().count
                }.getOrDefault(-1L)
                val pendingSubsCount = runCatching {
                    db.collection("submissions").whereEqualTo("status", "pending")
                        .count().get(AggregateSource.SERVER).await().count
                }.getOrDefault(-1L)
                val openReportsCount = runCatching {
                    db.collection("reports").whereEqualTo("status", "open")
                        .count().get(AggregateSource.SERVER).await().count
                }.getOrDefault(-1L)

                val logsSnap = db.collection("auditLog")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(40)
                    .get().await()
                val logsArr = JSONArray()
                logsSnap.documents.forEach { d ->
                    logsArr.put(JSONObject().apply {
                        put("id", d.id)
                        put("actorUid", d.getString("actorUid") ?: "")
                        put("actorLabel", d.getString("actorLabel") ?: "")
                        put("action", d.getString("action") ?: "")
                        put("targetType", d.getString("targetType") ?: "")
                        put("targetId", d.getString("targetId") ?: "")
                        put("details", d.getString("details") ?: "")
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                    })
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("usersCount", usersCount)
                    put("pendingSubmissions", pendingSubsCount)
                    put("openReports", openReportsCount)
                    put("logs", logsArr)
                }.toString()
                activity.evaluateJs("window.onAdminStatsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminStatsLoaded?.($payload);")
            }
        }
    }
}
