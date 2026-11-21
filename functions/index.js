const { initializeApp } = require("firebase-admin/app");
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { onDocumentCreated, onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const {
  computeAuthoritativeExpiryDate,
  deleteStorageObjectForMessage,
  runScheduledCleanup,
} = require("./cleanupCore");

initializeApp();

const DATABASE_ID = "ai-studio-android-talkback-6b398d18-d5c2-41eb-8893-aab5e5ba0932";
const db = getFirestore(DATABASE_ID);
const bucket = getStorage().bucket();

async function normalizeCreatedMessageExpiry(event) {
  const snap = event.data;
  if (!snap) return;

  const data = snap.data() || {};
  const createdDate = data.createdAt && typeof data.createdAt.toDate === "function"
    ? data.createdAt.toDate()
    : new Date();
  const authoritativeExpiry = computeAuthoritativeExpiryDate(createdDate);

  const currentExpiry = data.expiresAt && typeof data.expiresAt.toDate === "function"
    ? data.expiresAt.toDate().getTime()
    : 0;

  if (Math.abs(currentExpiry - authoritativeExpiry.getTime()) > 2000) {
    await snap.ref.update({
      expiresAt: Timestamp.fromDate(authoritativeExpiry),
    });
  }
}

exports.enforceConversationMessageTtl = onDocumentCreated(
  {
    document: "conversations/{conversationId}/messages/{messageId}",
    database: DATABASE_ID,
  },
  normalizeCreatedMessageExpiry
);

exports.enforceChannelMessageTtl = onDocumentCreated(
  {
    document: "servers/{serverId}/channels/{channelId}/messages/{messageId}",
    database: DATABASE_ID,
  },
  normalizeCreatedMessageExpiry
);

exports.purgeConversationAttachmentOnDelete = onDocumentDeleted(
  {
    document: "conversations/{conversationId}/messages/{messageId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    await deleteStorageObjectForMessage(bucket, snap.data()).catch(() => {});
  }
);

exports.purgeChannelAttachmentOnDelete = onDocumentDeleted(
  {
    document: "servers/{serverId}/channels/{channelId}/messages/{messageId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    await deleteStorageObjectForMessage(bucket, snap.data()).catch(() => {});
  }
);

exports.scheduledMessageCleanup = onSchedule(
  {
    schedule: "every 5 minutes",
    timeZone: "Etc/UTC",
  },
  async () => {
    await runScheduledCleanup(db, bucket, new Date(), Timestamp, FieldValue);
  }
);
