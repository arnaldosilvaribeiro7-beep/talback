const MESSAGE_TTL_MS = 3 * 60 * 60 * 1000;
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

function computeAuthoritativeExpiryDate(createdAtDate) {
  const baseMs = createdAtDate instanceof Date ? createdAtDate.getTime() : Date.now();
  return new Date(baseMs + MESSAGE_TTL_MS);
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

async function deleteExpiredQueryDocs(db, bucket, queryFactory, cutoffDate, FieldValue) {
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
      if (typeof data.conversationId === "string" && data.conversationId.length > 0) {
        touchedConversationIds.add(data.conversationId);
      }
      storageTasks.push(
        deleteStorageObjectForMessage(bucket, data)
          .then((deleted) => {
            if (deleted) deletedFiles += 1;
          })
          .catch(() => {})
      );
      batch.delete(doc.ref);
      deletedDocs += 1;
    }

    await Promise.all(storageTasks);
    await batch.commit();

    if (snapshot.docs.length < BATCH_LIMIT) break;
  }

  for (const convId of touchedConversationIds) {
    await scrubConversationPreviewIfStale(db, convId, cutoffDate, FieldValue).catch(() => {});
  }

  return { deletedDocs, deletedFiles, scrubbedConversations: touchedConversationIds.size };
}

async function runScheduledCleanup(db, bucket, nowDate, Timestamp, FieldValue) {
  const cutoffDate = new Date(nowDate.getTime() - MESSAGE_TTL_MS);

  const byExpiresAt = await deleteExpiredQueryDocs(
    db,
    bucket,
    () => db.collectionGroup("messages").where("expiresAt", "<=", Timestamp.fromDate(nowDate)),
    cutoffDate,
    FieldValue
  );

  const byCreatedAt = await deleteExpiredQueryDocs(
    db,
    bucket,
    () => db.collectionGroup("messages").where("createdAt", "<=", Timestamp.fromDate(cutoffDate)),
    cutoffDate,
    FieldValue
  );

  return {
    deletedDocs: byExpiresAt.deletedDocs + byCreatedAt.deletedDocs,
    deletedFiles: byExpiresAt.deletedFiles + byCreatedAt.deletedFiles,
  };
}

module.exports = {
  MESSAGE_TTL_MS,
  BATCH_LIMIT,
  extractStoragePath,
  computeAuthoritativeExpiryDate,
  deleteStorageObjectForMessage,
  scrubConversationPreviewIfStale,
  runScheduledCleanup,
};
