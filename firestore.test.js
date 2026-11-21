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
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob can query messages with memberUids filter and add reaction
  await assertSucceeds(
    bob
      .collection("conversations")
      .doc("conv_1")
      .collection("messages")
      .where("memberUids", "array-contains", BOB_UID)
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
        createdAt: pastDate(100),
        updatedAt: pastDate(100),
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
