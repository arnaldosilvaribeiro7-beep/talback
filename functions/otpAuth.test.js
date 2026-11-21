const { test } = require("node:test");
const assert = require("node:assert/strict");
const {
  OTP_TTL_MS,
  normalizeGmail,
  normalizeUsername,
  hashOtpCode,
  verifyOtpHash,
  OtpHttpError,
  requestGmailVerificationCode,
  verifyGmailCodeAndIssueToken,
} = require("./otpAuthCore");

function createInMemoryOtpEnv() {
  const store = {
    email_verifications: new Map(),
    usernames: new Map(),
    users: new Map(),
  };
  const authUsersByEmail = new Map();
  const authUsersByUid = new Map();
  let uidSeq = 1;

  const Timestamp = {
    fromDate: (d) => ({ toDate: () => d, _date: d }),
  };

  const makeDocRef = (colName, docId, subDocs = new Map()) => ({
    id: docId,
    path: `${colName}/${docId}`,
    get: async () => ({
      exists: store[colName].has(docId),
      data: () => store[colName].get(docId),
    }),
    set: async (payload) => {
      store[colName].set(docId, { ...payload });
    },
    update: async (payload) => {
      const existing = store[colName].get(docId) || {};
      store[colName].set(docId, { ...existing, ...payload });
    },
    delete: async () => {
      store[colName].delete(docId);
    },
    collection: (subName) => ({
      doc: (subId) => ({
        path: `${colName}/${docId}/${subName}/${subId}`,
        _key: `${colName}/${docId}/${subName}/${subId}`,
        _subMap: subDocs,
      }),
    }),
  });

  const subCollectionData = new Map();

  const db = {
    collection: (colName) => ({
      doc: (docId) => makeDocRef(colName, docId, subCollectionData),
    }),
    runTransaction: async (fn) => {
      const tx = {
        get: async (ref) => ref.get(),
        set: (ref, payload) => {
          if (ref._subMap) {
            ref._subMap.set(ref._key, { ...payload });
          } else {
            const parts = ref.path.split("/");
            store[parts[0]].set(parts[1], { ...payload });
          }
        },
      };
      return fn(tx);
    },
  };

  const adminAuth = {
    getUserByEmail: async (email) => {
      const user = authUsersByEmail.get(email);
      if (!user) {
        const err = new Error("User not found");
        err.code = "auth/user-not-found";
        throw err;
      }
      return user;
    },
    createUser: async ({ email, emailVerified, displayName }) => {
      const uid = `uid_${uidSeq++}`;
      const user = { uid, email, emailVerified, displayName };
      authUsersByEmail.set(email, user);
      authUsersByUid.set(uid, user);
      return user;
    },
    updateUser: async (uid, patch) => {
      const user = authUsersByUid.get(uid);
      if (user) Object.assign(user, patch);
      return user;
    },
    deleteUser: async (uid) => {
      const user = authUsersByUid.get(uid);
      if (user) {
        authUsersByEmail.delete(user.email);
        authUsersByUid.delete(uid);
      }
    },
    createCustomToken: async (uid, claims) => `custom_token_${uid}_${claims.authMethod}`,
  };

  return { store, subCollectionData, authUsersByEmail, db, adminAuth, Timestamp };
}

test("normalizeGmail strictly permits @gmail.com addresses and rejects other domains", () => {
  assert.equal(normalizeGmail("  Usuario.Teste+1@Gmail.COM "), "usuario.teste+1@gmail.com");
  assert.equal(normalizeGmail("user@outlook.com"), null);
  assert.equal(normalizeGmail("user@googlemail.com"), null);
  assert.equal(normalizeGmail("user@gmail.com\r\nBcc:evil@evil.com"), null);
  assert.equal(normalizeGmail(""), null);
  assert.equal(normalizeUsername("  @Ana_Dev.01 "), "ana_dev.01");
  assert.equal(normalizeUsername("ab"), null);
});

test("hashOtpCode and verifyOtpHash verify 6-digit codes securely with scrypt", () => {
  const hash = hashOtpCode("482910", "salt_abc");
  assert.equal(verifyOtpHash("482910", "salt_abc", hash), true);
  assert.equal(verifyOtpHash("482911", "salt_abc", hash), false);
  assert.equal(verifyOtpHash("12345", "salt_abc", hash), false);
});

test("Gmail OTP signup flow: sends 6-digit code, verifies it, creates account, issues token, and deletes challenge", async () => {
  const { store, subCollectionData, db, adminAuth, Timestamp } = createInMemoryOtpEnv();
  const sentEmails = [];
  const t0 = new Date("2026-11-21T14:00:00.000Z");

  const reqResult = await requestGmailVerificationCode({
    db,
    adminAuth,
    Timestamp,
    nowDate: t0,
    sendMailFn: async (mail) => {
      sentEmails.push(mail);
    },
    body: {
      email: "Maria.Silva@gmail.com",
      username: "maria.dev",
      displayName: "Maria Silva",
      isNewAccount: true,
    },
  });

  assert.equal(reqResult.email, "maria.silva@gmail.com");
  assert.equal(sentEmails.length, 1);
  assert.match(sentEmails[0].code, /^\d{6}$/);
  assert.equal(store.email_verifications.size, 1);

  const savedChallenge = store.email_verifications.get(reqResult.challengeId);
  assert.notEqual(savedChallenge.codeHash, sentEmails[0].code); // Must NOT store plaintext OTP
  assert.equal(
    savedChallenge.expiresAt.toDate().getTime() - t0.getTime(),
    OTP_TTL_MS
  );

  // Wrong code increments attempts without creating user
  await assert.rejects(
    () =>
      verifyGmailCodeAndIssueToken({
        db,
        adminAuth,
        Timestamp,
        nowDate: new Date(t0.getTime() + 5000),
        body: {
          challengeId: reqResult.challengeId,
          email: "maria.silva@gmail.com",
          code: "000000",
        },
      }),
    (err) => err instanceof OtpHttpError && err.status === 401
  );
  assert.equal(store.email_verifications.get(reqResult.challengeId).attempts, 1);

  // Correct code issues custom token, creates Firestore profile/username, and immediately deletes challenge
  const verifyResult = await verifyGmailCodeAndIssueToken({
    db,
    adminAuth,
    Timestamp,
    nowDate: new Date(t0.getTime() + 10_000),
    body: {
      challengeId: reqResult.challengeId,
      email: "maria.silva@gmail.com",
      code: sentEmails[0].code,
    },
  });

  assert.equal(verifyResult.customToken, "custom_token_uid_1_gmail_otp");
  assert.equal(store.email_verifications.size, 0); // Ephemeral challenge deleted!
  assert.equal(store.usernames.get("maria.dev").userId, "uid_1");
  assert.equal(store.users.get("uid_1").displayName, "Maria Silva");
  assert.equal(
    subCollectionData.get("users/uid_1/private/account").email,
    "maria.silva@gmail.com"
  );
});

test("Gmail OTP locks out and deletes challenge after 5 failed attempts or expiration", async () => {
  const { store, db, adminAuth, Timestamp } = createInMemoryOtpEnv();
  const sentEmails = [];
  const t0 = new Date("2026-11-21T14:00:00.000Z");

  const reqResult = await requestGmailVerificationCode({
    db,
    adminAuth,
    Timestamp,
    nowDate: t0,
    sendMailFn: async (mail) => sentEmails.push(mail),
    body: {
      email: "joao@gmail.com",
      username: "joao_tb",
      isNewAccount: true,
    },
  });

  const wrongCode = sentEmails[0].code === "111111" ? "222222" : "111111";
  for (let i = 0; i < 4; i += 1) {
    await assert.rejects(
      () =>
        verifyGmailCodeAndIssueToken({
          db,
          adminAuth,
          Timestamp,
          nowDate: new Date(t0.getTime() + 1000),
          body: {
            challengeId: reqResult.challengeId,
            email: "joao@gmail.com",
            code: wrongCode,
          },
        }),
      (err) => err instanceof OtpHttpError && err.status === 401
    );
  }
  // 5th failure deletes challenge immediately and returns 429
  await assert.rejects(
    () =>
      verifyGmailCodeAndIssueToken({
        db,
        adminAuth,
        Timestamp,
        nowDate: new Date(t0.getTime() + 1000),
        body: {
          challengeId: reqResult.challengeId,
          email: "joao@gmail.com",
          code: wrongCode,
        },
      }),
    (err) => err instanceof OtpHttpError && err.status === 429
  );
  assert.equal(store.email_verifications.size, 0);
});

test("Gmail OTP rejects non-gmail domain and cleans up challenge when SMTP fails", async () => {
  const { store, db, adminAuth, Timestamp } = createInMemoryOtpEnv();
  await assert.rejects(
    () =>
      requestGmailVerificationCode({
        db,
        adminAuth,
        Timestamp,
        sendMailFn: async () => {},
        body: { email: "alguem@hotmail.com", username: "alguem", isNewAccount: true },
      }),
    (err) => err instanceof OtpHttpError && err.status === 400
  );

  await assert.rejects(
    () =>
      requestGmailVerificationCode({
        db,
        adminAuth,
        Timestamp,
        sendMailFn: async () => {
          throw new OtpHttpError(503, "SMTP down");
        },
        body: { email: "ana@gmail.com", username: "ana_ok", isNewAccount: true },
      }),
    (err) => err instanceof OtpHttpError && err.status === 503
  );
  assert.equal(store.email_verifications.size, 0);
});
