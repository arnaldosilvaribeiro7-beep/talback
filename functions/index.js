const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, Timestamp, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");
const { onDocumentCreated, onDocumentUpdated, onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onRequest } = require("firebase-functions/v2/https");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const nodemailer = require("nodemailer");
const {
  computeAuthoritativeExpiryDate,
  deleteStorageObjectForMessage,
  purgeSignalsForCall,
  runScheduledCleanup,
} = require("./cleanupCore");
const {
  createSmtpTransporterFromEnv,
  OtpHttpError,
  requestGmailVerificationCode,
  verifyGmailCodeAndIssueToken,
} = require("./otpAuthCore");

initializeApp();

const DATABASE_ID = "ai-studio-android-talkback-6b398d18-d5c2-41eb-8893-aab5e5ba0932";
const REGION = "us-central1";
const db = getFirestore(DATABASE_ID);
const adminAuth = getAuth();
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
    region: REGION,
  },
  normalizeCreatedMessageExpiry
);

exports.enforceChannelMessageTtl = onDocumentCreated(
  {
    document: "servers/{serverId}/channels/{channelId}/messages/{messageId}",
    database: DATABASE_ID,
    region: REGION,
  },
  normalizeCreatedMessageExpiry
);

exports.purgeConversationAttachmentOnDelete = onDocumentDeleted(
  {
    document: "conversations/{conversationId}/messages/{messageId}",
    database: DATABASE_ID,
    region: REGION,
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
    region: REGION,
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    await deleteStorageObjectForMessage(bucket, snap.data()).catch(() => {});
  }
);

exports.purgeCallSignalsOnCallTerminal = onDocumentUpdated(
  {
    document: "calls/{callId}",
    database: DATABASE_ID,
    region: REGION,
  },
  async (event) => {
    const afterData = event.data && event.data.after ? event.data.after.data() : null;
    if (!afterData) return;
    if (afterData.status === "ended" || afterData.status === "declined") {
      await purgeSignalsForCall(db, event.params.callId).catch(() => {});
    }
  }
);

exports.scheduledMessageCleanup = onSchedule(
  {
    schedule: "every 5 minutes",
    timeZone: "Etc/UTC",
    region: REGION,
  },
  async () => {
    await runScheduledCleanup(db, bucket, new Date(), Timestamp, FieldValue);
  }
);

async function sendVerificationCodeEmail({ to, code, isNewAccount, expiresMinutes }) {
  const smtp = createSmtpTransporterFromEnv(nodemailer, process.env);
  if (!smtp) {
    throw new OtpHttpError(
      503,
      "Serviço SMTP não configurado no servidor Firebase Functions (defina SMTP_USER e SMTP_PASS)."
    );
  }
  const actionLabel = isNewAccount ? "criação da sua conta" : "login na sua conta";
  await smtp.transporter.sendMail({
    from: `"Talkback" <${smtp.from}>`,
    to,
    subject: `Talkback — Código de verificação: ${code}`,
    text:
      `Olá!\n\n` +
      `Use o código de 6 dígitos abaixo para confirmar ${actionLabel} no Talkback:\n\n` +
      `${code}\n\n` +
      `Este código expira automaticamente em ${expiresMinutes} minutos.\n` +
      `Se você não solicitou este código, ignore este e-mail.`,
    html:
      `<div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;">` +
      `<h2 style="color:#5865F2;margin-bottom:12px;">Talkback</h2>` +
      `<p>Use o código de 6 dígitos abaixo para confirmar ${actionLabel}:</p>` +
      `<div style="font-size:32px;font-weight:bold;letter-spacing:8px;padding:16px 20px;` +
      `background:#1E1F22;color:#FFFFFF;border-radius:10px;text-align:center;margin:20px 0;">${code}</div>` +
      `<p style="color:#666;font-size:13px;">Válido por ${expiresMinutes} minutos. Uso único.</p>` +
      `</div>`,
  });
}

exports.requestEmailCode = onRequest({ cors: true, region: REGION }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "Método não permitido." });
    return;
  }
  try {
    const result = await requestGmailVerificationCode({
      db,
      adminAuth,
      Timestamp,
      sendMailFn: sendVerificationCodeEmail,
      body: req.body || {},
    });
    res.status(200).json(result);
  } catch (err) {
    const status = err instanceof OtpHttpError ? err.status : 500;
    const message = err instanceof OtpHttpError
      ? err.message
      : "Erro interno ao enviar código de verificação.";
    res.status(status).json({ error: message });
  }
});

exports.verifyEmailCode = onRequest({ cors: true, region: REGION }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).json({ error: "Método não permitido." });
    return;
  }
  try {
    const result = await verifyGmailCodeAndIssueToken({
      db,
      adminAuth,
      Timestamp,
      body: req.body || {},
    });
    res.status(200).json(result);
  } catch (err) {
    const status = err instanceof OtpHttpError ? err.status : 500;
    const message = err instanceof OtpHttpError
      ? err.message
      : "Erro interno ao validar código de verificação.";
    res.status(status).json({ error: message });
  }
});
