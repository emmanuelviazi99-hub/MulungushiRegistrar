// services/code.service.js
//
// Verification codes for sign-up and password reset.
//
// The rules (they match what the Android app's AuthService promises):
//   - a code lives 10 minutes
//   - 5 tries per code, then the person must ask for a new one
//   - a new code can be requested at most every 30 seconds
//   - asking for a new code kills the old one
//   - a code works once
//   - the code is stored only as an HMAC keyed with a server secret
//
// All time comparisons use the database clock (NOW(3)) so they cannot be
// skewed by the Node process and MySQL disagreeing about the time zone.

const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const { withTransaction } = require('../utils/transaction');
const { normaliseCode } = require('../utils/validators');

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I — easy to read aloud and type
const CODE_TTL_SECONDS = 10 * 60;
const MAX_ATTEMPTS = 5;
const RESEND_COOLDOWN_SECONDS = 30;
const VERIFIED_WINDOW_SECONDS = 30 * 60; // time allowed to finish sign-up after the code was accepted

function pepper() {
  const p = process.env.CODE_PEPPER || process.env.JWT_SECRET;
  if (!p) throw new Error('Set CODE_PEPPER (or JWT_SECRET) so verification codes can be hashed');
  return p;
}

function generateCode() {
  let body = '';
  for (let i = 0; i < 6; i += 1) body += ALPHABET[crypto.randomInt(ALPHABET.length)];
  return `MU-${body}`; // 32^6 ≈ 1.07 billion possibilities, with only 5 tries each
}

function hashCode({ purpose, email, code }) {
  return crypto.createHmac('sha256', pepper()).update(`${purpose}\n${email}\n${normaliseCode(code)}`).digest('hex');
}

/**
 * Creates a fresh code, invalidating any earlier one for the same email+purpose.
 * Returns the plain code ONCE, for the caller to hand to the delivery service.
 */
async function issue({ purpose, email, studentId = null, staffId = null, accountId = null, phone = null }) {
  const [recent] = await pool.query(
    `SELECT TIMESTAMPDIFF(SECOND, created_at, NOW(3)) AS age
       FROM verification_codes WHERE email = ? AND purpose = ?
      ORDER BY code_id DESC LIMIT 1`,
    [email, purpose]
  );
  if (recent.length > 0 && recent[0].age < RESEND_COOLDOWN_SECONDS) {
    throw new ApiError(429, 'RESEND_TOO_SOON', 'Please wait a moment before asking for another code.', {
      retryAfterSeconds: RESEND_COOLDOWN_SECONDS - recent[0].age,
    });
  }

  const code = generateCode();
  const codeId = await withTransaction(async (conn) => {
    // A new code replaces any earlier live one for the same email + purpose.
    await conn.query(
      'UPDATE verification_codes SET consumed_at = NOW(3) WHERE email = ? AND purpose = ? AND consumed_at IS NULL',
      [email, purpose]
    );
    const [result] = await conn.query(
      `INSERT INTO verification_codes
         (purpose, email, code_hash, student_id, staff_id, account_id, phone, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, DATE_ADD(NOW(3), INTERVAL ? SECOND))`,
      [purpose, email, hashCode({ purpose, email, code }), studentId, staffId, accountId, phone, CODE_TTL_SECONDS]
    );
    return result.insertId;
  });
  return { codeId, code, expiresInSeconds: CODE_TTL_SECONDS, resendAfterSeconds: RESEND_COOLDOWN_SECONDS };
}

/** Removes a code that could not be delivered, so it does not count as "sent". */
async function discard(codeId) {
  await pool.query('DELETE FROM verification_codes WHERE code_id = ?', [codeId]);
}

/**
 * Checks `code` against the newest live code for this email+purpose.
 * Marks it verified on success. Throws a coded ApiError otherwise.
 */
async function verify({ purpose, email, code }) {
  const [rows] = await pool.query(
    `SELECT *, (expires_at <= NOW(3)) AS expired
       FROM verification_codes
      WHERE email = ? AND purpose = ? AND consumed_at IS NULL
      ORDER BY code_id DESC LIMIT 1`,
    [email, purpose]
  );
  const row = rows[0];
  // "No live code" and "expired" are one answer on purpose: it does not reveal
  // whether anyone ever asked for a code for this email.
  if (!row || row.expired) {
    throw new ApiError(410, 'CODE_EXPIRED', 'That code has expired. Please ask for a new one.');
  }

  // Reserve an attempt BEFORE comparing. Because the increment is one atomic
  // statement guarded by `attempts < MAX`, a flood of parallel guesses can never
  // get more than MAX comparisons in — the "5 tries" limit cannot be raced.
  const [reserved] = await pool.query(
    'UPDATE verification_codes SET attempts = attempts + 1 WHERE code_id = ? AND attempts < ? AND consumed_at IS NULL',
    [row.code_id, MAX_ATTEMPTS]
  );
  if (reserved.affectedRows !== 1) {
    throw new ApiError(429, 'TOO_MANY_TRIES', 'Too many wrong attempts. Please ask for a new code.', { triesLeft: 0 });
  }

  const given = Buffer.from(hashCode({ purpose, email, code }), 'hex');
  const expected = Buffer.from(row.code_hash, 'hex');
  const matches = given.length === expected.length && crypto.timingSafeEqual(given, expected);

  if (!matches) {
    const [[{ attempts }]] = await pool.query('SELECT attempts FROM verification_codes WHERE code_id = ?', [row.code_id]);
    const triesLeft = Math.max(0, MAX_ATTEMPTS - attempts);
    if (triesLeft === 0) {
      throw new ApiError(429, 'TOO_MANY_TRIES', 'Too many wrong attempts. Please ask for a new code.', { triesLeft });
    }
    throw new ApiError(400, 'BAD_CODE', 'That code is not right.', { triesLeft });
  }

  await pool.query('UPDATE verification_codes SET verified_at = COALESCE(verified_at, NOW(3)) WHERE code_id = ?', [row.code_id]);
  return row;
}

/** The step after verify(): finds the code the person already proved they hold. */
async function requireVerified({ purpose, email }) {
  const [rows] = await pool.query(
    `SELECT * FROM verification_codes
      WHERE email = ? AND purpose = ? AND consumed_at IS NULL AND verified_at IS NOT NULL
        AND verified_at > DATE_SUB(NOW(3), INTERVAL ? SECOND)
      ORDER BY code_id DESC LIMIT 1`,
    [email, purpose, VERIFIED_WINDOW_SECONDS]
  );
  if (rows.length === 0) {
    throw new ApiError(403, 'NOT_VERIFIED', 'Please verify your code first.');
  }
  return rows[0];
}

/** Marks a code used up. Returns false if it was already used (so completion can only happen once). */
async function consume(conn, codeId) {
  const [result] = await conn.query(
    'UPDATE verification_codes SET consumed_at = NOW(3) WHERE code_id = ? AND consumed_at IS NULL',
    [codeId]
  );
  return result.affectedRows === 1;
}

module.exports = {
  issue,
  discard,
  verify,
  requireVerified,
  consume,
  generateCode,
  CODE_TTL_SECONDS,
  MAX_ATTEMPTS,
  RESEND_COOLDOWN_SECONDS,
};
