const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  MESSAGE_TTL_MS,
  SIGNAL_TTL_MS,
  MODERATION_LOG_TTL_MS,
  CALL_TTL_MS,
  extractStoragePath,
  computeAuthoritativeExpiryDate,
  deleteStorageObjectForMessage,
  purgeSignalsForCall,
  runScheduledCleanup,
} = require("./cleanupCore");

test("extractStoragePath resolves explicit storagePath, gs:// URLs, and HTTPS download URLs", () => {
  assert.equal(
    extractStoragePath({
      attachmentStoragePath: "chat_attachments/u1/pic.png",
      attachmentUrl: "https://example.com/ignored",
    }),
    "chat_attachments/u1/pic.png"
  );

  assert.equal(
    extractStoragePath({
      attachmentUrl:
        "https://firebasestorage.googleapis.com/v0/b/gen-lang-client-0067289898.firebasestorage.app/o/chat_attachments%2Fu1%2Fabc.jpg?alt=media&token=123",
    }),
    "chat_attachments/u1/abc.jpg"
  );

  assert.equal(
    extractStoragePath({
      attachmentUrl: "gs://my-bucket/server_attachments/u2/doc.pdf",
    }),
    "server_attachments/u2/doc.pdf"
  );

  assert.equal(extractStoragePath({ text: "no attachment" }), null);
});

test("computeAuthoritativeExpiryDate adds exact TTL durations", () => {
  const created = new Date("2026-11-21T10:00:00.000Z");
  const expires = computeAuthoritativeExpiryDate(created);
  assert.equal(expires.getTime() - created.getTime(), MESSAGE_TTL_MS);
  assert.equal(expires.toISOString(), "2026-11-21T13:00:00.000Z");

  assert.equal(SIGNAL_TTL_MS, 60 * 60 * 1000);
  assert.equal(MODERATION_LOG_TTL_MS, 24 * 60 * 60 * 1000);
  assert.equal(CALL_TTL_MS, 24 * 60 * 60 * 1000);
});

test("runScheduledCleanup deletes expired messages, logs, signals, calls, verifications, requests, and Storage blobs", async () => {
  const now = new Date("2026-11-21T15:00:00.000Z");
  const oldTime = new Date("2026-11-21T11:50:00.000Z");

  const deletedDocPaths = [];
  const deletedStoragePaths = [];
  const updatedConversations = {};

  const makeMockDoc = (id, path, data) => ({
    id,
    ref: { id, path },
    data: () => data,
  });

  const groupFixtures = {
    messages: [
      makeMockDoc("m1", "conversations/conv_1/messages/m1", {
        conversationId: "conv_1",
        attachmentStoragePath: "chat_attachments/alice/photo.png",
        expiresAt: { toDate: () => new Date("2026-11-21T14:50:00.000Z") },
      }),
      makeMockDoc("m2", "conversations/conv_1/messages/m2", {
        conversationId: "conv_1",
        attachmentUrl: "https://firebasestorage.googleapis.com/v0/b/b/o/server_attachments%2Fbob%2Faudio.m4a?alt=media",
        expiresAt: { toDate: () => new Date("2026-11-21T14:55:00.000Z") },
      }),
    ],
    signals: [
      makeMockDoc("sig_1", "calls/c1/signals/sig_1", { callId: "c1" }),
    ],
    moderation_logs: [
      makeMockDoc("log_1", "servers/s1/moderation_logs/log_1", { action: "kick" }),
    ],
  };

  const rootFixtures = {
    email_verifications: [makeMockDoc("otp_1", "email_verifications/otp_1", { email: "a@gmail.com" })],
    calls: [makeMockDoc("call_old", "calls/call_old", { status: "ended" })],
    friend_requests: [makeMockDoc("req_old", "friend_requests/req_old", { status: "pending" })],
  };

  const seenGroup = new Set();
  const seenRoot = new Set();

  const mockQuery = (bucketKey, fixturesMap, seenSet) => ({
    where: (field) => ({
      limit: () => ({
        get: async () => {
          const key = `${bucketKey}:${field}`;
          if (seenSet.has(key) || field !== "expiresAt") {
            return { empty: true, docs: [] };
          }
          seenSet.add(key);
          const docs = fixturesMap[bucketKey] || [];
          return { empty: docs.length === 0, docs };
        },
      }),
    }),
  });

  const mockDb = {
    collectionGroup: (name) => mockQuery(name, groupFixtures, seenGroup),
    collection: (name) => ({
      ...mockQuery(name, rootFixtures, seenRoot),
      doc: (id) => ({
        get: async () => ({
          exists: name === "conversations" && id === "conv_1",
          data: () => ({ lastMessageAt: { toDate: () => oldTime }, lastMessageText: "Secret" }),
        }),
        update: async (payload) => {
          updatedConversations[id] = payload;
        },
        collection: () => ({
          limit: () => ({ get: async () => ({ empty: true, docs: [] }) }),
        }),
      }),
    }),
    batch: () => {
      const refs = [];
      return {
        delete: (ref) => refs.push(ref),
        commit: async () => {
          refs.forEach((r) => deletedDocPaths.push(r.path));
        },
      };
    },
  };

  const mockBucket = {
    file: (path) => ({
      delete: async () => {
        deletedStoragePaths.push(path);
      },
    }),
  };

  const MockTimestamp = { fromDate: (d) => d };
  const MockFieldValue = {
    delete: () => "__DELETE__",
    serverTimestamp: () => "__SERVER_TS__",
  };

  const result = await runScheduledCleanup(mockDb, mockBucket, now, MockTimestamp, MockFieldValue);
  assert.equal(result.deletedMessages, 2);
  assert.equal(result.deletedFiles, 2);
  assert.equal(result.deletedSignals, 1);
  assert.equal(result.deletedLogs, 1);
  assert.equal(result.deletedVerifications, 1);
  assert.equal(result.deletedCalls, 1);
  assert.equal(result.deletedFriendRequests, 1);
  assert.equal(result.deletedDocs, 7);
  assert.deepEqual(deletedDocPaths, [
    "conversations/conv_1/messages/m1",
    "conversations/conv_1/messages/m2",
    "email_verifications/otp_1",
    "calls/c1/signals/sig_1",
    "servers/s1/moderation_logs/log_1",
    "calls/call_old",
    "friend_requests/req_old",
  ]);
  assert.deepEqual(deletedStoragePaths, [
    "chat_attachments/alice/photo.png",
    "server_attachments/bob/audio.m4a",
  ]);
  assert.equal(updatedConversations.conv_1.lastMessageText, "");
  assert.equal(updatedConversations.conv_1.lastMessageSenderUid, "__DELETE__");
});

test("purgeSignalsForCall deletes all signal docs for an ended call", async () => {
  const deleted = [];
  let fetched = false;
  const mockDb = {
    collection: () => ({
      doc: () => ({
        collection: () => ({
          limit: () => ({
            get: async () => {
              if (fetched) return { empty: true, docs: [] };
              fetched = true;
              return {
                empty: false,
                docs: [
                  { id: "s1", ref: { id: "s1" }, data: () => ({}) },
                  { id: "s2", ref: { id: "s2" }, data: () => ({}) },
                ],
              };
            },
          }),
        }),
      }),
    }),
    batch: () => {
      const refs = [];
      return {
        delete: (ref) => refs.push(ref),
        commit: async () => refs.forEach((r) => deleted.push(r.id)),
      };
    },
  };

  const count = await purgeSignalsForCall(mockDb, "call_1");
  assert.equal(count, 2);
  assert.deepEqual(deleted, ["s1", "s2"]);
});

test("deleteStorageObjectForMessage handles missing bucket gracefully", async () => {
  const deleted = await deleteStorageObjectForMessage(null, { attachmentStoragePath: "a/b.png" });
  assert.equal(deleted, false);
});
