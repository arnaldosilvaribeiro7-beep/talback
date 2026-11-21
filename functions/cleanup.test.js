const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  MESSAGE_TTL_MS,
  extractStoragePath,
  computeAuthoritativeExpiryDate,
  deleteStorageObjectForMessage,
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

test("computeAuthoritativeExpiryDate adds exactly 3 hours to server createdAt", () => {
  const created = new Date("2026-11-21T10:00:00.000Z");
  const expires = computeAuthoritativeExpiryDate(created);
  assert.equal(expires.getTime() - created.getTime(), MESSAGE_TTL_MS);
  assert.equal(expires.toISOString(), "2026-11-21T13:00:00.000Z");
});

test("runScheduledCleanup deletes expired docs, Storage blobs, and stale conversation previews", async () => {
  const now = new Date("2026-11-21T15:00:00.000Z");
  const oldTime = new Date("2026-11-21T11:50:00.000Z");

  const deletedDocIds = [];
  const deletedStoragePaths = [];
  const updatedConversations = {};

  const makeMockDoc = (id, data) => ({
    id,
    ref: { id, path: `conversations/${data.conversationId}/messages/${id}` },
    data: () => data,
  });

  const expiredDocs = [
    makeMockDoc("m1", {
      conversationId: "conv_1",
      attachmentStoragePath: "chat_attachments/alice/photo.png",
      expiresAt: { toDate: () => new Date("2026-11-21T14:50:00.000Z") },
    }),
    makeMockDoc("m2", {
      conversationId: "conv_1",
      attachmentUrl: "https://firebasestorage.googleapis.com/v0/b/b/o/server_attachments%2Fbob%2Faudio.m4a?alt=media",
      expiresAt: { toDate: () => new Date("2026-11-21T14:55:00.000Z") },
    }),
  ];

  let queryCallCount = 0;
  const mockDb = {
    collectionGroup: () => ({
      where: () => ({
        limit: () => ({
          get: async () => {
            queryCallCount += 1;
            if (queryCallCount === 1) {
              return { empty: false, docs: expiredDocs };
            }
            return { empty: true, docs: [] };
          },
        }),
      }),
    }),
    collection: (name) => ({
      doc: (id) => ({
        get: async () => ({
          exists: name === "conversations" && id === "conv_1",
          data: () => ({ lastMessageAt: { toDate: () => oldTime }, lastMessageText: "Secret" }),
        }),
        update: async (payload) => {
          updatedConversations[id] = payload;
        },
      }),
    }),
    batch: () => {
      const refs = [];
      return {
        delete: (ref) => refs.push(ref),
        commit: async () => {
          refs.forEach((r) => deletedDocIds.push(r.id));
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
  assert.equal(result.deletedDocs, 2);
  assert.equal(result.deletedFiles, 2);
  assert.deepEqual(deletedDocIds, ["m1", "m2"]);
  assert.deepEqual(deletedStoragePaths, [
    "chat_attachments/alice/photo.png",
    "server_attachments/bob/audio.m4a",
  ]);
  assert.equal(updatedConversations.conv_1.lastMessageText, "");
  assert.equal(updatedConversations.conv_1.lastMessageSenderUid, "__DELETE__");
});

test("deleteStorageObjectForMessage handles missing bucket gracefully", async () => {
  const deleted = await deleteStorageObjectForMessage(null, { attachmentStoragePath: "a/b.png" });
  assert.equal(deleted, false);
});
