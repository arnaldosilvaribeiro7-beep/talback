const crypto = require("node:crypto");

const OTP_TTL_MS = 10 * 60 * 1000; // 10 minutes
const RESEND_COOLDOWN_MS = 60 * 1000; // 60 seconds
const MAX_VERIFY_ATTEMPTS = 5;
const GMAIL_REGEX = /^[a-z0-9._%+-]+@gmail\.com$/;
const USERNAME_REGEX = /^[a-z0-9_.]{3,30}$/;
const CODE_REGEX = /^\d{6}$/;

function normalizeGmail(rawEmail) {
  if (typeof rawEmail !== "string") return null;
  const cleaned = rawEmail.trim().toLowerCase();
  if (!cleaned || cleaned.length > 160 || /[\r\n]/.test(cleaned)) return null;
  if (!GMAIL_REGEX.test(cleaned)) return null;
  return cleaned;
}

function normalizeUsername(rawUsername) {
  if (typeof rawUsername !== "string") return null;
  const cleaned = rawUsername.trim().toLowerCase().replace(/^@+/, "");
  if (!USERNAME_REGEX.test(cleaned)) return null;
  return cleaned;
}

function sanitizeDisplayName(rawName, fallbackUsername) {
  if (typeof rawName === "string") {
    const cleaned = rawName.replace(/[\r\n]+/g, " ").trim().slice(0, 50);
    if (cleaned.length > 0) return cleaned;
  }
  return (fallbackUsername || "Talkback").slice(0, 50);
}

function generateSixDigitCode() {
  return String(crypto.randomInt(100000, 1000000));
}

function generateSalt() {
  return crypto.randomBytes(16).toString("hex");
}

function hashOtpCode(code, salt) {
  return crypto.scryptSync(String(code), String(salt), 32).toString("hex");
}

function verifyOtpHash(code, salt, expectedHash) {
  if (!CODE_REGEX.test(String(code || ""))) return false;
  if (typeof salt !== "string" || typeof expectedHash !== "string") return false;
  const actualBuf = Buffer.from(hashOtpCode(code, salt), "hex");
  const expectedBuf = Buffer.from(expectedHash, "hex");
  if (actualBuf.length !== expectedBuf.length) return false;
  return crypto.timingSafeEqual(actualBuf, expectedBuf);
}

function buildChallengeId(email) {
  return crypto.createHash("sha256").update(email).digest("hex").slice(0, 40);
}

function toDateOrNull(value) {
  if (!value) return null;
  if (typeof value.toDate === "function") return value.toDate();
  if (value instanceof Date) return value;
  return null;
}

function createSmtpTransporterFromEnv(nodemailerModule, env = process.env) {
  const user = (env.SMTP_USER || "").trim();
  const pass = (env.SMTP_PASS || "").trim();
  if (!user || !pass) {
    return null;
  }
  const host = (env.SMTP_HOST || "smtp.gmail.com").trim();
  const port = parseInt(env.SMTP_PORT || "465", 10);
  const secure = env.SMTP_SECURE ? env.SMTP_SECURE === "true" : port === 465;
  return {
    from: (env.SMTP_FROM || user).trim(),
    transporter: nodemailerModule.createTransport({
      host,
      port,
      secure,
      auth: { user, pass },
    }),
  };
}

class OtpHttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function requestGmailVerificationCode({
  db,
  adminAuth,
  Timestamp,
  sendMailFn,
  body,
  nowDate = new Date(),
}) {
  const email = normalizeGmail(body && body.email);
  if (!email) {
    throw new OtpHttpError(
      400,
      "Somente endereços @gmail.com válidos são aceitos para verificação."
    );
  }

  const isNewAccount = Boolean(body && body.isNewAccount);
  let username = null;
  let displayName = null;

  if (isNewAccount) {
    username = normalizeUsername(body && body.username);
    if (!username) {
      throw new OtpHttpError(
        400,
        "Escolha um @usuário válido (3 a 30 caracteres: letras minúsculas, números, _ ou .)."
      );
    }
    displayName = sanitizeDisplayName(body && body.displayName, username);

    const usernameSnap = await db.collection("usernames").doc(username).get();
    if (usernameSnap.exists) {
      throw new OtpHttpError(409, `O @${username} já está em uso. Escolha outro nome de usuário.`);
    }
  }

  let existingAuthUser = null;
  try {
    existingAuthUser = await adminAuth.getUserByEmail(email);
  } catch (err) {
    if (!err || err.code !== "auth/user-not-found") {
      throw err;
    }
  }

  if (isNewAccount && existingAuthUser) {
    throw new OtpHttpError(
      409,
      "Este @gmail.com já possui conta no Talkback. Use a aba Entrar."
    );
  }
  if (!isNewAccount && !existingAuthUser) {
    throw new OtpHttpError(
      404,
      "Nenhuma conta encontrada para este @gmail.com. Selecione Criar conta."
    );
  }

  const challengeId = buildChallengeId(email);
  const challengeRef = db.collection("email_verifications").doc(challengeId);
  const existingChallengeSnap = await challengeRef.get();
  if (existingChallengeSnap.exists) {
    const existingData = existingChallengeSnap.data() || {};
    const lastCreated = toDateOrNull(existingData.createdAt);
    const expiresAt = toDateOrNull(existingData.expiresAt);
    if (
      lastCreated &&
      expiresAt &&
      expiresAt.getTime() > nowDate.getTime() &&
      nowDate.getTime() - lastCreated.getTime() < RESEND_COOLDOWN_MS
    ) {
      const waitSec = Math.ceil(
        (RESEND_COOLDOWN_MS - (nowDate.getTime() - lastCreated.getTime())) / 1000
      );
      throw new OtpHttpError(
        429,
        `Aguarde ${waitSec}s antes de solicitar um novo código de confirmação.`
      );
    }
  }

  const code = generateSixDigitCode();
  const salt = generateSalt();
  const codeHash = hashOtpCode(code, salt);
  const expiresDate = new Date(nowDate.getTime() + OTP_TTL_MS);

  const challengePayload = {
    email,
    isNewAccount,
    codeHash,
    salt,
    attempts: 0,
    maxAttempts: MAX_VERIFY_ATTEMPTS,
    createdAt: Timestamp.fromDate(nowDate),
    expiresAt: Timestamp.fromDate(expiresDate),
  };
  if (username) challengePayload.username = username;
  if (displayName) challengePayload.displayName = displayName;

  await challengeRef.set(challengePayload);

  try {
    await sendMailFn({
      to: email,
      code,
      isNewAccount,
      expiresMinutes: Math.round(OTP_TTL_MS / 60000),
    });
  } catch (mailErr) {
    await challengeRef.delete().catch(() => {});
    if (mailErr instanceof OtpHttpError) throw mailErr;
    throw new OtpHttpError(
      502,
      "Não foi possível enviar o e-mail de verificação. Tente novamente em instantes."
    );
  }

  return {
    challengeId,
    email,
    expiresInSeconds: Math.round(OTP_TTL_MS / 1000),
    resendAfterSeconds: Math.round(RESEND_COOLDOWN_MS / 1000),
  };
}

async function verifyGmailCodeAndIssueToken({
  db,
  adminAuth,
  Timestamp,
  body,
  nowDate = new Date(),
}) {
  const challengeId =
    body && typeof body.challengeId === "string" ? body.challengeId.trim() : "";
  const email = normalizeGmail(body && body.email);
  const code = body && typeof body.code === "string" ? body.code.trim() : "";

  if (!challengeId || !email) {
    throw new OtpHttpError(400, "Desafio de verificação inválido.");
  }
  if (!CODE_REGEX.test(code)) {
    throw new OtpHttpError(400, "Digite o código numérico de 6 dígitos enviado ao seu @gmail.com.");
  }

  const challengeRef = db.collection("email_verifications").doc(challengeId);
  const snap = await challengeRef.get();
  if (!snap.exists) {
    throw new OtpHttpError(404, "Código expirado ou inválido. Solicite um novo código.");
  }

  const data = snap.data() || {};
  if (data.email !== email) {
    throw new OtpHttpError(403, "O endereço @gmail.com não corresponde a este código.");
  }

  const expiresDate = toDateOrNull(data.expiresAt);
  if (!expiresDate || expiresDate.getTime() <= nowDate.getTime()) {
    await challengeRef.delete().catch(() => {});
    throw new OtpHttpError(410, "Este código expirou. Solicite um novo código de 6 dígitos.");
  }

  const attempts = Number.isInteger(data.attempts) ? data.attempts : 0;
  const maxAttempts = Number.isInteger(data.maxAttempts) ? data.maxAttempts : MAX_VERIFY_ATTEMPTS;
  if (attempts >= maxAttempts) {
    await challengeRef.delete().catch(() => {});
    throw new OtpHttpError(429, "Limite de tentativas excedido. Solicite um novo código.");
  }

  const matches = verifyOtpHash(code, data.salt, data.codeHash);
  if (!matches) {
    const nextAttempts = attempts + 1;
    if (nextAttempts >= maxAttempts) {
      await challengeRef.delete().catch(() => {});
      throw new OtpHttpError(429, "Código incorreto. Limite de tentativas atingido; solicite outro código.");
    }
    await challengeRef.update({ attempts: nextAttempts });
    const remaining = maxAttempts - nextAttempts;
    throw new OtpHttpError(
      401,
      `Código incorreto. Restam ${remaining} tentativa(s).`
    );
  }

  // Code verified: immediately consume/delete the ephemeral challenge document
  await challengeRef.delete().catch(() => {});

  const isNewAccount = Boolean(data.isNewAccount);
  let authUser = null;
  try {
    authUser = await adminAuth.getUserByEmail(email);
  } catch (err) {
    if (!err || err.code !== "auth/user-not-found") {
      throw err;
    }
  }

  if (!authUser && !isNewAccount) {
    throw new OtpHttpError(404, "Conta não encontrada para este @gmail.com.");
  }

  if (!authUser) {
    const chosenUsername =
      normalizeUsername(body && body.username) ||
      normalizeUsername(data.username) ||
      `tb_${crypto.randomBytes(4).toString("hex")}`;
    const chosenDisplayName = sanitizeDisplayName(
      (body && body.displayName) || data.displayName,
      chosenUsername
    );

    const usernameRef = db.collection("usernames").doc(chosenUsername);
    const existingClaim = await usernameRef.get();
    if (existingClaim.exists) {
      throw new OtpHttpError(
        409,
        `O @${chosenUsername} acabou de ser reservado. Solicite um novo código com outro @usuário.`
      );
    }

    authUser = await adminAuth.createUser({
      email,
      emailVerified: true,
      displayName: chosenDisplayName,
    });

    const uid = authUser.uid;
    const nowTs = Timestamp.fromDate(nowDate);
    try {
      await db.runTransaction(async (tx) => {
        const claimSnap = await tx.get(usernameRef);
        if (claimSnap.exists) {
          throw new OtpHttpError(409, `O @${chosenUsername} já está em uso.`);
        }
        const userRef = db.collection("users").doc(uid);
        const privateRef = userRef.collection("private").doc("account");

        tx.set(usernameRef, {
          userId: uid,
          username: chosenUsername,
          displayName: chosenDisplayName,
          createdAt: nowTs,
          updatedAt: nowTs,
        });
        tx.set(userRef, {
          userId: uid,
          username: chosenUsername,
          displayName: chosenDisplayName,
          bio: "Disponível no Talkback",
          statusText: "",
          presence: "online",
          primaryColorHex: 0x5865f2,
          secondaryColorHex: 0x4752c4,
          gradientEnabled: false,
          createdAt: nowTs,
          updatedAt: nowTs,
        });
        tx.set(privateRef, {
          userId: uid,
          email,
          blockedUids: [],
          notifyMessages: true,
          notifyFriendRequests: true,
          notifyInvites: true,
          notifyCalls: true,
          notifyMentions: true,
          createdAt: nowTs,
          updatedAt: nowTs,
        });
      });
    } catch (txErr) {
      await adminAuth.deleteUser(uid).catch(() => {});
      throw txErr;
    }
  } else if (!authUser.emailVerified) {
    await adminAuth.updateUser(authUser.uid, { emailVerified: true }).catch(() => {});
  }

  const customToken = await adminAuth.createCustomToken(authUser.uid, {
    authMethod: "gmail_otp",
  });

  return {
    customToken,
    uid: authUser.uid,
    email,
  };
}

module.exports = {
  OTP_TTL_MS,
  RESEND_COOLDOWN_MS,
  MAX_VERIFY_ATTEMPTS,
  normalizeGmail,
  normalizeUsername,
  sanitizeDisplayName,
  generateSixDigitCode,
  generateSalt,
  hashOtpCode,
  verifyOtpHash,
  buildChallengeId,
  createSmtpTransporterFromEnv,
  OtpHttpError,
  requestGmailVerificationCode,
  verifyGmailCodeAndIssueToken,
};
