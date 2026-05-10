/**
 * Nomad Bridge relay function.
 *
 * Architecture: Two phones (Home/H and Travel/T) pair via a 6-digit code.
 * Once paired, all SMS/command payloads flow phone -> this function -> FCM -> partner phone.
 * No third-party messaging service. Project-internal Firestore stores only the pair record:
 *   bridges/{pairingId} = { code, codeExpiresAt, secret, hToken, tToken, createdAt, paired }
 *
 * Auth model: every request other than /pair/start carries (pairingId, secret).
 * Secret is a random 32-byte URL-safe string issued at pair start, returned to both sides.
 */

import { onRequest, HttpsError } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import * as logger from "firebase-functions/logger";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import * as crypto from "crypto";

initializeApp();
setGlobalOptions({ region: "us-central1", maxInstances: 10 });

const db = getFirestore();
const fcm = getMessaging();

const CODE_TTL_MS = 10 * 60 * 1000; // 10 minutes
const PAIR_COLLECTION = "bridges";

/**
 * Account-key gate. Every request outside of /health must present the correct
 * X-Account-Key header. The key is set via the ACCOUNT_KEY environment variable
 * on the Cloud Function (never committed to source).
 *
 * Returns true if the request is authorized. Writes the 401/500 response and
 * returns false otherwise — caller should `return` immediately.
 */
function requireAccountKey(req: any, res: any): boolean {
  const expected = process.env.ACCOUNT_KEY;
  if (!expected) {
    // Fail closed: if the function was deployed without a key, refuse everything
    // rather than silently running open.
    logger.error("ACCOUNT_KEY env var not set — refusing all requests");
    res.status(500).json({ error: "Server not configured" });
    return false;
  }
  const provided = req.get("X-Account-Key") || req.get("x-account-key");
  if (provided !== expected) {
    res.status(401).json({ error: "Unauthorized" });
    return false;
  }
  return true;
}

type Role = "home" | "travel";

interface BridgeDoc {
  code: string;
  codeExpiresAt: number;
  secret: string;
  hToken: string | null;
  tToken: string | null;
  createdAt: number;
  paired: boolean;
}

function randomCode(): string {
  // 6-digit numeric, leading zeros OK.
  const n = crypto.randomInt(0, 1_000_000);
  return n.toString().padStart(6, "0");
}

function randomSecret(): string {
  return crypto.randomBytes(24).toString("base64url");
}

function jsonOk(res: any, body: any) {
  res.status(200).json(body);
}

function jsonErr(res: any, status: number, message: string) {
  res.status(status).json({ error: message });
}

async function readJson(req: any): Promise<any> {
  if (req.body && typeof req.body === "object") return req.body;
  return {};
}

/**
 * POST /pairStart
 * Body: { token: string, role: "home" | "travel" }
 * Returns: { pairingId, code, secret, expiresAt }
 *
 * Either side may initiate. Typically Home shows the code; Travel enters it.
 */
export const pairStart = onRequest(async (req, res) => {
  if (req.method !== "POST") return jsonErr(res, 405, "POST only");
  if (!requireAccountKey(req, res)) return;
  const body = await readJson(req);
  const { token, role } = body as { token?: string; role?: Role };
  if (!token || (role !== "home" && role !== "travel")) {
    return jsonErr(res, 400, "Required: token, role in {home,travel}");
  }

  // Generate a unique 6-digit code (retry on collision against unexpired codes).
  let code = "";
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate = randomCode();
    const collide = await db
      .collection(PAIR_COLLECTION)
      .where("code", "==", candidate)
      .where("codeExpiresAt", ">", Date.now())
      .limit(1)
      .get();
    if (collide.empty) {
      code = candidate;
      break;
    }
  }
  if (!code) return jsonErr(res, 500, "Could not allocate code");

  const secret = randomSecret();
  const now = Date.now();
  const doc: BridgeDoc = {
    code,
    codeExpiresAt: now + CODE_TTL_MS,
    secret,
    hToken: role === "home" ? token : null,
    tToken: role === "travel" ? token : null,
    createdAt: now,
    paired: false,
  };
  const ref = await db.collection(PAIR_COLLECTION).add(doc);
  logger.info("pairStart", { pairingId: ref.id, role });
  return jsonOk(res, { pairingId: ref.id, code, secret, expiresAt: doc.codeExpiresAt });
});

/**
 * POST /pairFinish
 * Body: { code: string, token: string, role: "home" | "travel" }
 * Returns: { pairingId, secret, partnerToken }
 *
 * Completes pairing: looks up bridge by code, fills in the missing token, returns the partner's token.
 */
export const pairFinish = onRequest(async (req, res) => {
  if (req.method !== "POST") return jsonErr(res, 405, "POST only");
  if (!requireAccountKey(req, res)) return;
  const body = await readJson(req);
  const { code, token, role } = body as { code?: string; token?: string; role?: Role };
  if (!code || !token || (role !== "home" && role !== "travel")) {
    return jsonErr(res, 400, "Required: code, token, role in {home,travel}");
  }

  const snap = await db
    .collection(PAIR_COLLECTION)
    .where("code", "==", code)
    .where("codeExpiresAt", ">", Date.now())
    .limit(1)
    .get();
  if (snap.empty) return jsonErr(res, 404, "Code not found or expired");

  const ref = snap.docs[0].ref;
  const data = snap.docs[0].data() as BridgeDoc;

  if (role === "home") {
    if (data.hToken && data.hToken !== token) {
      return jsonErr(res, 409, "Home side already paired with a different token");
    }
    await ref.update({ hToken: token, paired: data.tToken != null });
    if (!data.tToken) return jsonErr(res, 425, "Waiting for partner");
    return jsonOk(res, { pairingId: ref.id, secret: data.secret, partnerToken: data.tToken });
  } else {
    if (data.tToken && data.tToken !== token) {
      return jsonErr(res, 409, "Travel side already paired with a different token");
    }
    await ref.update({ tToken: token, paired: data.hToken != null });
    if (!data.hToken) return jsonErr(res, 425, "Waiting for partner");
    return jsonOk(res, { pairingId: ref.id, secret: data.secret, partnerToken: data.hToken });
  }
});

/**
 * POST /pairStatus
 * Body: { pairingId: string, secret: string, role: "home" | "travel" }
 * Returns: { paired: bool, partnerToken?: string }
 *
 * Used by the side that initiated /pairStart to poll until the partner has joined.
 */
export const pairStatus = onRequest(async (req, res) => {
  if (req.method !== "POST") return jsonErr(res, 405, "POST only");
  if (!requireAccountKey(req, res)) return;
  const body = await readJson(req);
  const { pairingId, secret, role } = body as {
    pairingId?: string;
    secret?: string;
    role?: Role;
  };
  if (!pairingId || !secret || (role !== "home" && role !== "travel")) {
    return jsonErr(res, 400, "Required: pairingId, secret, role");
  }
  const ref = db.collection(PAIR_COLLECTION).doc(pairingId);
  const snap = await ref.get();
  if (!snap.exists) return jsonErr(res, 404, "Bridge not found");
  const data = snap.data() as BridgeDoc;
  if (data.secret !== secret) return jsonErr(res, 403, "Bad secret");
  const partner = role === "home" ? data.tToken : data.hToken;
  return jsonOk(res, { paired: !!partner, partnerToken: partner ?? null });
});

/**
 * POST /updateToken
 * Body: { pairingId, secret, role, token }
 * Updates this side's FCM token (rotates over time).
 */
export const updateToken = onRequest(async (req, res) => {
  if (req.method !== "POST") return jsonErr(res, 405, "POST only");
  if (!requireAccountKey(req, res)) return;
  const body = await readJson(req);
  const { pairingId, secret, role, token } = body as {
    pairingId?: string;
    secret?: string;
    role?: Role;
    token?: string;
  };
  if (!pairingId || !secret || !token || (role !== "home" && role !== "travel")) {
    return jsonErr(res, 400, "Required: pairingId, secret, role, token");
  }
  const ref = db.collection(PAIR_COLLECTION).doc(pairingId);
  const snap = await ref.get();
  if (!snap.exists) return jsonErr(res, 404, "Bridge not found");
  const data = snap.data() as BridgeDoc;
  if (data.secret !== secret) return jsonErr(res, 403, "Bad secret");
  await ref.update(role === "home" ? { hToken: token } : { tToken: token });
  return jsonOk(res, { ok: true });
});

/**
 * POST /send
 * Body: { pairingId, secret, role, payload }
 * Forwards `payload` (any JSON-serializable object) to the partner via FCM data message.
 */
export const send = onRequest(async (req, res) => {
  if (req.method !== "POST") return jsonErr(res, 405, "POST only");
  if (!requireAccountKey(req, res)) return;
  const body = await readJson(req);
  const { pairingId, secret, role, payload } = body as {
    pairingId?: string;
    secret?: string;
    role?: Role;
    payload?: unknown;
  };
  if (!pairingId || !secret || !payload || (role !== "home" && role !== "travel")) {
    return jsonErr(res, 400, "Required: pairingId, secret, role, payload");
  }
  const ref = db.collection(PAIR_COLLECTION).doc(pairingId);
  const snap = await ref.get();
  if (!snap.exists) return jsonErr(res, 404, "Bridge not found");
  const data = snap.data() as BridgeDoc;
  if (data.secret !== secret) return jsonErr(res, 403, "Bad secret");

  const targetToken = role === "home" ? data.tToken : data.hToken;
  if (!targetToken) return jsonErr(res, 412, "Partner not paired");

  // FCM data-only message. High priority so the device wakes from doze for SMS-like latency.
  const payloadJson = JSON.stringify(payload);
  if (payloadJson.length > 3500) {
    return jsonErr(res, 413, "Payload too large (>3500 bytes)");
  }
  try {
    const messageId = await fcm.send({
      token: targetToken,
      data: { payload: payloadJson },
      android: {
        priority: "high",
        ttl: 7 * 24 * 3600 * 1000,
      },
    });
    await ref.update({ lastMessageAt: FieldValue.serverTimestamp() });
    return jsonOk(res, { ok: true, messageId });
  } catch (e: any) {
    logger.error("FCM send failed", { err: e?.message, code: e?.code });
    // If the partner token is invalid (uninstalled / cleared data), surface that.
    const code = e?.errorInfo?.code || e?.code;
    if (code === "messaging/registration-token-not-registered") {
      return jsonErr(res, 410, "Partner token invalid (gone)");
    }
    return jsonErr(res, 502, "FCM send failed: " + (e?.message ?? "unknown"));
  }
});

/**
 * GET /health -- liveness probe.
 */
export const health = onRequest(async (_req, res) => {
  res.status(200).json({ ok: true, ts: Date.now() });
});
