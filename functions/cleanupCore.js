const MESSAGE_TTL_MS = 3 * 60 * 60 * 1000; // 3 hours
const SIGNAL_TTL_MS = 60 * 60 * 1000; // 1 hour
const MODERATION_LOG_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const CALL_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours
const FRIEND_REQUEST_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days
const OTP_CHALLENGE_TTL_MS = 10 * 60 * 1000; // 10 minutes
const BATCH_LIMIT = 400;

function extractStoragePath(messageData) {
  if (!messageData || typeof messageData !== "object") return null;

  const explicit = messageData.attachmentStoragePath;
  if (typeof explicit === "string" && explicit.trim().length > 0) {
    return explicit.trim();
  }

  const url = messageData.attachmentUrl;
  if (typeof url !== "string" || url.trim().length === 0) return null;

  const trimmed = url.trim();
  if (trimmed.startsWith("gs://")) {
    const withoutScheme = trimmed.slice(5);
    const slashIdx = withoutScheme.indexOf("/");
    if (slashIdx > 0 && slashIdx + 1 < withoutScheme.length) {
      return withoutScheme.slice(slashIdx + 1);
    }
    return null;
  }

  const oMarker = "/o/";
  const markerIdx = trimmed.indexOf(oMarker);
  if (markerIdx !== -1) {
    const afterO = trimmed.slice(markerIdx + oMarker.length);
    const queryIdx = afterO.indexOf("?");
    const encodedPath = queryIdx === -1 ? afterO : afterO.slice(0, queryIdx);
    if (encodedPath.length > 0) {
      try {
        return decodeURIComponent(encodedPath);
      } catch (_) {
        return encodedPath;
      }
    }
  }

  return null;
}

function computeAuthoritativeExpiryDate(createdAtDate, ttlMs = MESSAGE_TTL_MS) {
  const baseMs = createdAtDate instanceof Date ? createdAtDate.getTime() : Date.now();
  return new Date(baseMs + ttlMs);
}

async function deleteStorageObjectForMessage(bucket, messageData) {
  if (!bucket) return false;
  const storagePath = extractStoragePath(messageData);
  if (!storagePath) return false;

  await bucket.file(storagePath).delete({ ignoreNotFound: true });
  return true;
}

async function scrubConversationPreviewIfStale(db, conversationId, cutoffDate, FieldValue) {
  if (!conversationId) return false;
  const convRef = db.collection("conversations").doc(conversationId);
  const convSnap = await convRef.get();
  if (!convSnap.exists) return false;

  const convData = convSnap.data() || {};
  const lastAt = convData.lastMessageAt && typeof convData.lastMessageAt.toDate === "function"
    ? convData.lastMessageAt.toDate()
    : convData.lastMessageAt instanceof Date
      ? convData.lastMessageAt
      : null;

  if (!lastAt || lastAt.getTime() <= cutoffDate.getTime()) {
    await convRef.update({
      lastMessageText: "",
      lastMessageSenderUid: FieldValue.delete(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return true;
  }
  return false;
}

async function deleteExpiredQueryDocs(
  db,
  bucket,
  queryFactory,
  cutoffDate,
  FieldValue,
  { deleteStorage = false, scrubConversations = false } = {}
) {
  let deletedDocs = 0;
  let deletedFiles = 0;
  const touchedConversationIds = new Set();

  while (true) {
    const snapshot = await queryFactory().limit(BATCH_LIMIT).get();
    if (!snapshot || snapshot.empty) break;

    const batch = db.batch();
    const storageTasks = [];

    for (const doc of snapshot.docs) {
      const data = typeof doc.data === "function" ? doc.data() : {};
      if (scrubConversations && typeof data.conversationId === "string" && data.conversationId.length > 0) {
        touchedConversationIds.add(data.conversationId);
      }
      if (deleteStorage) {
        storageTasks.push(
          deleteStorageObjectForMessage(bucket, data)
            .then((deleted) => {
              if (deleted) deletedFiles += 1;
            })
            .catch(() => {})
        );
      }
      batch.delete(doc.ref);
      deletedDocs += 1;
    }

    if (storageTasks.length > 0) {
      await Promise.all(storageTasks);
    }
    await batch.commit();

    if (snapshot.docs.length < BATCH_LIMIT) break;
  }

  if (scrubConversations) {
    for (const convId of touchedConversationIds) {
      await scrubConversationPreviewIfStale(db, convId, cutoffDate, FieldValue).catch(() => {});
    }
  }

  return { deletedDocs, deletedFiles, scrubbedConversations: touchedConversationIds.size };
}

async function purgeSignalsForCall(db, callId) {
  if (!db || !callId) return 0;
  const signalsCol = db.collection("calls").doc(callId).collection("signals");
  const res = await deleteExpiredQueryDocs(
    db,
    null,
    () => signalsCol,
    new Date(),
    null,
    { deleteStorage: false, scrubConversations: false }
  );
  return res.deletedDocs;
}

async function cleanupEphemeralCollection(db, queryFactory, cutoffDate) {
  const res = await deleteExpiredQueryDocs(db, null, queryFactory, cutoffDate, null, {
    deleteStorage: false,
    scrubConversations: false,
  });
  return res.deletedDocs;
}

async function runScheduledCleanup(db, bucket, nowDate, Timestamp, FieldValue) {
  const nowTs = Timestamp.fromDate(nowDate);
  const messageCutoff = new Date(nowDate.getTime() - MESSAGE_TTL_MS);
  const signalCutoff = new Date(nowDate.getTime() - SIGNAL_TTL_MS);
  const logCutoff = new Date(nowDate.getTime() - MODERATION_LOG_TTL_MS);
  const callCutoff = new Date(nowDate.getTime() - CALL_TTL_MS);
  const otpCutoff = new Date(nowDate.getTime() - OTP_CHALLENGE_TTL_MS);
  const reqCutoff = new Date(nowDate.getTime() - FRIEND_REQUEST_TTL_MS);

  // 1. Ephemeral messages (3h) + Storage attachments + stale DM previews
  const msgByExpiry = await deleteExpiredQueryDocs(
    db,
    bucket,
    () => db.collectionGroup("messages").where("expiresAt", "<=", nowTs),
    messageCutoff,
    FieldValue,
    { deleteStorage: true, scrubConversations: true }
  );
  const msgByCreated = await deleteExpiredQueryDocs(
    db,
    bucket,
    () => db.collectionGroup("messages").where("createdAt", "<=", Timestamp.fromDate(messageCutoff)),
    messageCutoff,
    FieldValue,
    { deleteStorage: true, scrubConversations: true }
  );

  // 2. Gmail OTP verification challenges (10m)
  const deletedVerifications =
    (await cleanupEphemeralCollection(
      db,
      () => db.collection("email_verifications").where("expiresAt", "<=", nowTs),
      otpCutoff
    )) +
    (await cleanupEphemeralCollection(
      db,
      () => db.collection("email_verifications").where("createdAt", "<=", Timestamp.fromDate(otpCutoff)),
      otpCutoff
    ));

  // 3. WebRTC signaling packets (1h)
  const deletedSignals =
    (await cleanupEphemeralCollection(
      db,
      () => db.collectionGroup("signals").where("expiresAt", "<=", nowTs),
      signalCutoff
    )) +
    (await cleanupEphemeralCollection(
      db,
      () => db.collectionGroup("signals").where("createdAt", "<=", Timestamp.fromDate(signalCutoff)),
      signalCutoff
    ));

  // 4. Server moderation logs (24h)
  const deletedLogs =
    (await cleanupEphemeralCollection(
      db,
      () => db.collectionGroup("moderation_logs").where("expiresAt", "<=", nowTs),
      logCutoff
    )) +
    (await cleanupEphemeralCollection(
      db,
      () => db.collectionGroup("moderation_logs").where("createdAt", "<=", Timestamp.fromDate(logCutoff)),
      logCutoff
    ));

  // 5. Call sessions (24h)
  const deletedCalls =
    (await cleanupEphemeralCollection(
      db,
      () => db.collection("calls").where("expiresAt", "<=", nowTs),
      callCutoff
    )) +
    (await cleanupEphemeralCollection(
      db,
      () => db.collection("calls").where("createdAt", "<=", Timestamp.fromDate(callCutoff)),
      callCutoff
    ));

  // 6. Expired or already-resolved friend requests (7d)
  const deletedFriendRequests =
    (await cleanupEphemeralCollection(
      db,
      () => db.collection("friend_requests").where("expiresAt", "<=", nowTs),
      reqCutoff
    )) +
    (await cleanupEphemeralCollection(
      db,
      () => db.collection("friend_requests").where("createdAt", "<=", Timestamp.fromDate(reqCutoff)),
      reqCutoff
    ));

  const deletedMessages = msgByExpiry.deletedDocs + msgByCreated.deletedDocs;
  return {
    deletedDocs:
      deletedMessages +
      deletedVerifications +
      deletedSignals +
      deletedLogs +
      deletedCalls +
      deletedFriendRequests,
    deletedMessages,
    deletedFiles: msgByExpiry.deletedFiles + msgByCreated.deletedFiles,
    deletedVerifications,
    deletedSignals,
    deletedLogs,
    deletedCalls,
    deletedFriendRequests,
  };
}

module.exports = {
  MESSAGE_TTL_MS,
  SIGNAL_TTL_MS,
  MODERATION_LOG_TTL_MS,
  CALL_TTL_MS,
  FRIEND_REQUEST_TTL_MS,
  OTP_CHALLENGE_TTL_MS,
  BATCH_LIMIT,
  extractStoragePath,
  computeAuthoritativeExpiryDate,
  deleteStorageObjectForMessage,
  scrubConversationPreviewIfStale,
  purgeSignalsForCall,
  runScheduledCleanup,
};
