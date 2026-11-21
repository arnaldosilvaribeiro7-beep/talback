const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_1";
const BOB_UID = "bob_uid_2";
const CHARLIE_UID = "charlie_uid_3";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const pastDate = (offsetMs = 1000) => new Date(Date.now() - offsetMs);
const THREE_HOURS_MS = 3 * 60 * 60 * 1000;
const expiryFrom = (createdDate) => new Date(createdDate.getTime() + THREE_HOURS_MS);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated requests are denied across all collections", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(unauth.collection("conversations").get());
  await assertFails(unauth.collection("servers").get());
  await assertFails(unauth.collection("calls").get());
  await assertFails(
    unauth.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice",
      displayName: "Alice",
      bio: "",
      statusText: "",
      presence: "online",
      primaryColorHex: 0xff5865f2,
      secondaryColorHex: 0xff3ba55c,
      gradientEnabled: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("User profile, username claim, and private PII isolation", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = pastDate(5000);

  // Alice creates her username claim & public profile (matching Kotlin payload, no "id")
  await assertSucceeds(
    alice.collection("usernames").doc("alice.dev").set({
      userId: ALICE_UID,
      username: "alice.dev",
      displayName: "Alice Dev",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      username: "alice.dev",
      displayName: "Alice Dev",
      bio: "Engenheira Android",
      statusText: "Codando Talkback",
      presence: "online",
      primaryColorHex: 0xff5865f2,
      secondaryColorHex: 0xff4752c4,
      gradientEnabled: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alice writes private PII doc
  await assertSucceeds(
    alice.collection("users").doc(ALICE_UID).collection("private").doc("account").set({
      userId: ALICE_UID,
      email: "alice@example.com",
      blockedUids: [],
      notifyMessages: true,
      notifyFriendRequests: true,
      notifyInvites: true,
      notifyCalls: true,
      notifyMentions: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob can read Alice's public profile & lookup username, but CANNOT read Alice's private PII
  await assertSucceeds(bob.collection("users").doc(ALICE_UID).get());
  await assertSucceeds(bob.collection("usernames").doc("alice.dev").get());
  await assertFails(bob.collection("users").doc(ALICE_UID).collection("private").doc("account").get());

  // Bob cannot list users unfiltered or impersonate Alice
  await assertFails(bob.collection("users").get());
  await assertFails(
    bob.collection("users").doc(ALICE_UID).update({
      displayName: "Hacked",
      updatedAt: pastDate(100),
    })
  );

  // Shadow update with ghost field is rejected
  await assertFails(
    alice.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(100),
    })
  );
});

test("Friend requests and friendships lifecycle", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = pastDate(5000);

  await assertSucceeds(
    alice.collection("friend_requests").doc("req_1").set({
      fromUid: ALICE_UID,
      fromUsername: "alice.dev",
      fromDisplayName: "Alice",
      toUid: BOB_UID,
      toUsername: "bob.dev",
      toDisplayName: "Bob",
      status: "pending",
      participantUids: [ALICE_UID, BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Filtered query succeeds for participant, unfiltered fails
  await assertSucceeds(
    bob.collection("friend_requests").where("participantUids", "array-contains", BOB_UID).get()
  );
  await assertFails(bob.collection("friend_requests").get());

  // Charlie cannot read or accept Alice->Bob request
  await assertFails(charlie.collection("friend_requests").doc("req_1").get());
  await assertFails(
    charlie.collection("friend_requests").doc("req_1").update({
      status: "accepted",
      updatedAt: pastDate(100),
    })
  );

  // Alice (sender) cannot self-accept her own outgoing request
  await assertFails(
    alice.collection("friend_requests").doc("req_1").update({
      status: "accepted",
      updatedAt: pastDate(100),
    })
  );

  // Bob (recipient) accepts and creates friendship
  await assertSucceeds(
    bob.collection("friend_requests").doc("req_1").update({
      status: "accepted",
      updatedAt: pastDate(100),
    })
  );
  await assertSucceeds(
    bob.collection("friendships").doc("alice_bob").set({
      participantUids: [ALICE_UID, BOB_UID],
      userAUid: ALICE_UID,
      userAName: "Alice",
      userAUsername: "alice.dev",
      userBUid: BOB_UID,
      userBName: "Bob",
      userBUsername: "bob.dev",
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );
});

test("Private conversations, messages, reactions, and author-only edits", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = pastDate(5000);

  await assertSucceeds(
    alice.collection("conversations").doc("conv_1").set({
      type: "dm",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      lastMessageText: "",
      lastMessageAt: now,
      typingUids: [],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    alice.collection("conversations").doc("conv_1").collection("messages").doc("msg_1").set({
      conversationId: "conv_1",
      senderUid: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      text: "Oi Bob!",
      reactions: {},
      readByUids: [ALICE_UID],
      edited: false,
      deleted: false,
      expiresAt: expiryFrom(now),
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob can query active messages (within 3h server window) with memberUids + createdAt filter and add reaction
  await assertSucceeds(
    bob
      .collection("conversations")
      .doc("conv_1")
      .collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .where("createdAt", ">", new Date(Date.now() - THREE_HOURS_MS + 5000))
      .orderBy("createdAt", "asc")
      .get()
  );
  await assertSucceeds(
    bob.collection("conversations").doc("conv_1").collection("messages").doc("msg_1").update({
      reactions: { "🔥": [BOB_UID] },
      updatedAt: pastDate(200),
    })
  );

  // Bob cannot edit Alice's message text
  await assertFails(
    bob.collection("conversations").doc("conv_1").collection("messages").doc("msg_1").update({
      text: "Alterado por Bob",
      edited: true,
      updatedAt: pastDate(100),
    })
  );

  // Charlie cannot read DM
  await assertFails(charlie.collection("conversations").doc("conv_1").get());
  await assertFails(
    charlie.collection("conversations").doc("conv_1").collection("messages").doc("msg_1").get()
  );
});

test("Servers, RBAC permissions, invites, bans, and backend enforcement", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = pastDate(5000);

  // Alice creates server + owner membership + default #geral channel + invite
  await assertSucceeds(
    alice.collection("servers").doc("srv_1").set({
      name: "Talkback Brasil",
      description: "Comunidade oficial",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID],
      inviteCode: "TB2026",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    alice.collection("servers").doc("srv_1").collection("members").doc(ALICE_UID).set({
      userId: ALICE_UID,
      serverId: "srv_1",
      displayName: "Alice",
      roleId: "owner",
      roleName: "Dono",
      permissions: [
        "ADMIN_SERVER",
        "MANAGE_CHANNELS",
        "MANAGE_ROLES",
        "SEND_MESSAGES",
        "DELETE_MESSAGES",
        "CONNECT_VOICE",
        "INVITE_MEMBERS",
        "MANAGE_MEMBERS",
      ],
      joinedAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    alice.collection("servers").doc("srv_1").collection("channels").doc("ch_geral").set({
      serverId: "srv_1",
      name: "geral",
      topic: "Bate-papo geral",
      type: "text",
      position: 0,
      memberUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    alice.collection("invites").doc("TB2026").set({
      code: "TB2026",
      serverId: "srv_1",
      serverName: "Talkback Brasil",
      serverOwnerUid: ALICE_UID,
      creatorUid: ALICE_UID,
      maxUses: 10,
      uses: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot create a channel before joining or without MANAGE_CHANNELS
  await assertFails(
    bob.collection("servers").doc("srv_1").collection("channels").doc("ch_hack").set({
      serverId: "srv_1",
      name: "hacked",
      type: "text",
      position: 1,
      memberUids: [BOB_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob joins server via invite with default member permissions ONLY
  await assertSucceeds(bob.collection("invites").doc("TB2026").get());
  await assertSucceeds(
    bob.collection("servers").doc("srv_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: pastDate(300),
    })
  );
  // Bob cannot self-escalate to ADMIN_SERVER on join
  await assertFails(
    bob.collection("servers").doc("srv_1").collection("members").doc(BOB_UID).set({
      userId: BOB_UID,
      serverId: "srv_1",
      displayName: "Bob",
      roleId: "admin",
      roleName: "Admin",
      permissions: ["ADMIN_SERVER"],
      joinedAt: pastDate(300),
      updatedAt: pastDate(300),
    })
  );
  // Default Member role succeeds
  await assertSucceeds(
    bob.collection("servers").doc("srv_1").collection("members").doc(BOB_UID).set({
      userId: BOB_UID,
      serverId: "srv_1",
      displayName: "Bob",
      roleId: "member",
      roleName: "Membro",
      permissions: ["SEND_MESSAGES", "CONNECT_VOICE"],
      joinedAt: pastDate(300),
      updatedAt: pastDate(300),
    })
  );

  // Bob still cannot create a channel because he lacks MANAGE_CHANNELS on backend
  await assertFails(
    bob.collection("servers").doc("srv_1").collection("channels").doc("ch_bob").set({
      serverId: "srv_1",
      name: "canal-do-bob",
      type: "text",
      position: 1,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: pastDate(200),
      updatedAt: pastDate(200),
    })
  );

  // Bob CAN send a message in #geral
  const m1Time = pastDate(100);
  await assertSucceeds(
    bob
      .collection("servers")
      .doc("srv_1")
      .collection("channels")
      .doc("ch_geral")
      .collection("messages")
      .doc("m_1")
      .set({
        serverId: "srv_1",
        channelId: "ch_geral",
        senderUid: BOB_UID,
        senderName: "Bob",
        memberUids: [ALICE_UID, BOB_UID],
        text: "Salve galera!",
        reactions: {},
        readByUids: [BOB_UID],
        edited: false,
        deleted: false,
        expiresAt: expiryFrom(m1Time),
        createdAt: m1Time,
        updatedAt: m1Time,
      })
  );
});

test("Calls, WebRTC signaling, and terminal state lock", async () => {
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = pastDate(5000);

  await assertSucceeds(
    alice.collection("calls").doc("call_1").set({
      contextType: "dm",
      contextId: "conv_1",
      callerUid: ALICE_UID,
      callerName: "Alice",
      callType: "video",
      status: "ringing",
      participantUids: [ALICE_UID, BOB_UID],
      activeUids: [ALICE_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    alice.collection("calls").doc("call_1").collection("signals").doc("sig_1").set({
      callId: "call_1",
      fromUid: ALICE_UID,
      toUid: BOB_UID,
      type: "offer",
      sdp: "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n",
      participantUids: [ALICE_UID, BOB_UID],
      createdAt: now,
    })
  );

  // Charlie cannot snoop on call signals
  await assertFails(
    charlie.collection("calls").doc("call_1").collection("signals").doc("sig_1").get()
  );

  // Bob joins, then ends call
  await assertSucceeds(
    bob.collection("calls").doc("call_1").update({
      status: "active",
      activeUids: [ALICE_UID, BOB_UID],
      updatedAt: pastDate(200),
    })
  );
  await assertSucceeds(
    bob.collection("calls").doc("call_1").update({
      status: "ended",
      activeUids: [],
      updatedAt: pastDate(100),
    })
  );

  // Terminal state lock: cannot reopen an ended call
  await assertFails(
    alice.collection("calls").doc("call_1").update({
      status: "active",
      activeUids: [ALICE_UID],
      updatedAt: pastDate(50),
    })
  );
});

test("Ephemeral messages: 3h expiry validation, expired query blocking, hard-delete & sync", async () => {
  const assert = require("node:assert/strict");
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = pastDate(2000);

  await assertSucceeds(
    alice.collection("conversations").doc("conv_ephem").set({
      type: "dm",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      lastMessageText: "",
      lastMessageAt: now,
      typingUids: [],
      createdAt: now,
      updatedAt: now,
    })
  );

  // Reject tampered expiresAt (e.g., 24h in future or missing or past)
  await assertFails(
    alice.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_bad_long").set({
      conversationId: "conv_ephem",
      senderUid: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      text: "Tentativa de 24h",
      reactions: {},
      readByUids: [ALICE_UID],
      edited: false,
      deleted: false,
      expiresAt: new Date(now.getTime() + 24 * 60 * 60 * 1000),
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    alice.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_bad_past").set({
      conversationId: "conv_ephem",
      senderUid: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      text: "Ja expirada",
      reactions: {},
      readByUids: [ALICE_UID],
      edited: false,
      deleted: false,
      expiresAt: pastDate(100),
      createdAt: now,
      updatedAt: now,
    })
  );

  // Valid message with attachmentStoragePath & exact 3h expiry succeeds
  await assertSucceeds(
    alice.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_live").set({
      conversationId: "conv_ephem",
      senderUid: ALICE_UID,
      senderName: "Alice",
      memberUids: [ALICE_UID, BOB_UID],
      text: "Mensagem temporaria",
      attachmentUrl: "https://firebasestorage.googleapis.com/v0/b/test/o/file.jpg",
      attachmentName: "file.jpg",
      attachmentType: "image",
      attachmentStoragePath: `chat_attachments/${ALICE_UID}/file.jpg`,
      reactions: {},
      readByUids: [ALICE_UID],
      edited: false,
      deleted: false,
      expiresAt: expiryFrom(now),
      createdAt: now,
      updatedAt: now,
    })
  );

  // Seed an expired doc (> 3h old) simulating physical TTL/cron lag
  const expiredCreatedAt = new Date(Date.now() - THREE_HOURS_MS - 30_000);
  const expiredAt = new Date(Date.now() - 30_000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context
      .firestore()
      .collection("conversations")
      .doc("conv_ephem")
      .collection("messages")
      .doc("msg_expired")
      .set({
        conversationId: "conv_ephem",
        senderUid: ALICE_UID,
        senderName: "Alice",
        memberUids: [ALICE_UID, BOB_UID],
        text: "Mensagem vencida",
        reactions: {},
        readByUids: [ALICE_UID],
        edited: false,
        deleted: false,
        expiresAt: expiredAt,
        createdAt: expiredCreatedAt,
        updatedAt: expiredCreatedAt,
      });
  });

  // Expired message cannot be read directly or updated
  await assertFails(
    alice.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_expired").get()
  );
  await assertFails(
    bob.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_expired").get()
  );
  await assertFails(
    bob.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_expired").update({
      reactions: { "👍": [BOB_UID] },
      updatedAt: pastDate(100),
    })
  );

  // Stale/unfiltered queries seeking expired messages MUST fail with PERMISSION_DENIED
  await assertFails(
    bob
      .collection("conversations")
      .doc("conv_ephem")
      .collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );
  await assertFails(
    bob
      .collection("conversations")
      .doc("conv_ephem")
      .collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .where("createdAt", ">", new Date(Date.now() - 4 * 60 * 60 * 1000))
      .get()
  );

  // Active 3h-bounded query succeeds and returns ONLY active messages
  const activeSnap = await assertSucceeds(
    bob
      .collection("conversations")
      .doc("conv_ephem")
      .collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .where("createdAt", ">", new Date(Date.now() - THREE_HOURS_MS + 5000))
      .orderBy("createdAt", "asc")
      .get()
  );
  assert.equal(activeSnap.docs.length, 1);
  assert.equal(activeSnap.docs[0].id, "msg_live");

  // Soft-delete tombstone update is forbidden
  await assertFails(
    alice.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_live").update({
      text: "[Mensagem removida]",
      deleted: true,
      updatedAt: pastDate(50),
    })
  );

  // Non-participant Charlie CANNOT delete Alice/Bob's message
  await assertFails(
    charlie.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_live").delete()
  );

  // Whole conversation CANNOT be accidentally deleted even by owner
  await assertFails(alice.collection("conversations").doc("conv_ephem").delete());

  // Any participant (Bob, non-author!) CAN hard-delete msg_live immediately and Alice sees deletion
  await assertSucceeds(
    bob.collection("conversations").doc("conv_ephem").collection("messages").doc("msg_live").delete()
  );
  const afterDeleteSnap = await assertSucceeds(
    alice
      .collection("conversations")
      .doc("conv_ephem")
      .collection("messages")
      .where("memberUids", "array-contains", ALICE_UID)
      .where("createdAt", ">", new Date(Date.now() - THREE_HOURS_MS + 5000))
      .orderBy("createdAt", "asc")
      .get()
  );
  assert.equal(afterDeleteSnap.docs.length, 0);
});

test("Server channel ephemeral messages: expired query denial & hard-delete parity", async () => {
  const assert = require("node:assert/strict");
  const alice = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bob = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlie = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = pastDate(2000);

  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb.collection("servers").doc("srv_ephem").set({
      name: "Srv Ephem",
      description: "Teste",
      ownerUid: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      createdAt: now,
      updatedAt: now,
    });
    await adminDb.collection("servers").doc("srv_ephem").collection("members").doc(BOB_UID).set({
      userId: BOB_UID,
      serverId: "srv_ephem",
      displayName: "Bob",
      roleId: "member",
      roleName: "Membro",
      permissions: ["SEND_MESSAGES", "CONNECT_VOICE"],
      joinedAt: now,
      updatedAt: now,
    });
    await adminDb.collection("servers").doc("srv_ephem").collection("members").doc(CHARLIE_UID).set({
      userId: CHARLIE_UID,
      serverId: "srv_ephem",
      displayName: "Charlie",
      roleId: "member",
      roleName: "Membro",
      permissions: ["SEND_MESSAGES", "CONNECT_VOICE"],
      joinedAt: now,
      updatedAt: now,
    });
    await adminDb.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").set({
      serverId: "srv_ephem",
      name: "geral",
      type: "text",
      position: 0,
      memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      createdAt: now,
      updatedAt: now,
    });
    const expiredCreated = new Date(Date.now() - THREE_HOURS_MS - 60_000);
    await adminDb
      .collection("servers")
      .doc("srv_ephem")
      .collection("channels")
      .doc("ch_1")
      .collection("messages")
      .doc("old_m")
      .set({
        serverId: "srv_ephem",
        channelId: "ch_1",
        senderUid: BOB_UID,
        senderName: "Bob",
        memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
        text: "Antiga",
        reactions: {},
        readByUids: [BOB_UID],
        edited: false,
        deleted: false,
        expiresAt: new Date(Date.now() - 60_000),
        createdAt: expiredCreated,
        updatedAt: expiredCreated,
      });
  });

  // Expired channel message cannot be read directly or listed via unfiltered query
  await assertFails(
    bob.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").collection("messages").doc("old_m").get()
  );
  await assertFails(
    bob.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
      .get()
  );

  // Bob sends a live channel message
  const liveTime = pastDate(500);
  await assertSucceeds(
    bob.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").collection("messages").doc("new_m").set({
      serverId: "srv_ephem",
      channelId: "ch_1",
      senderUid: BOB_UID,
      senderName: "Bob",
      memberUids: [ALICE_UID, BOB_UID, CHARLIE_UID],
      text: "Ao vivo",
      reactions: {},
      readByUids: [BOB_UID],
      edited: false,
      deleted: false,
      expiresAt: expiryFrom(liveTime),
      createdAt: liveTime,
      updatedAt: liveTime,
    })
  );

  // Active 3h-bounded query returns ONLY new_m
  const chSnap = await assertSucceeds(
    charlie.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").collection("messages")
      .where("memberUids", "array-contains", CHARLIE_UID)
      .where("createdAt", ">", new Date(Date.now() - THREE_HOURS_MS + 5000))
      .orderBy("createdAt", "asc")
      .get()
  );
  assert.equal(chSnap.docs.length, 1);
  assert.equal(chSnap.docs[0].id, "new_m");

  // Charlie (non-author without DELETE_MESSAGES) cannot delete Bob's channel message, while Alice (owner) can hard-delete
  await assertFails(
    charlie.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").collection("messages").doc("new_m").delete()
  );
  await assertSucceeds(
    alice.collection("servers").doc("srv_ephem").collection("channels").doc("ch_1").collection("messages").doc("new_m").delete()
  );
});
