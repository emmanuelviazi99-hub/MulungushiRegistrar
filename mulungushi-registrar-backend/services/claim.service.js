// services/claim.service.js
//
// A claim code proves that the person registering on the phone is the same
// person the lecturer already entered on the roster. We store only a
// SHA-256 hash of the code (not the plain code) — the same reasoning as
// never storing plain-text passwords: if the database leaked, the codes
// themselves would still be useless.
//
// Flow:
//   lecturer creates student  --generateClaimCode-->  plain code shown once
//   student submits number+code  --verifyClaim-->  confirms the pairing,
//                                                    does NOT mark it used
//   student completes registration  --markClaimUsed--> consumed, single use

const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I, avoids ambiguity
const CODE_TTL_DAYS = 30;

function hashCode(code) {
  return crypto.createHash('sha256').update(code.toUpperCase().trim()).digest('hex');
}

/** Generates a human-friendly code like "MU-7K4Q". */
function generatePlainCode() {
  let body = '';
  for (let i = 0; i < 4; i++) {
    body += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  }
  return `MU-${body}`;
}

/**
 * Called by the lecturer-create-student flow. Creates a fresh claim code
 * for a student and returns the PLAIN code exactly once — it is the
 * lecturer's job to hand it to the student (e.g. printed on a slip); the
 * server never shows it again after this call.
 */
async function generateClaimCode(studentId) {
  const plainCode = generatePlainCode();
  const expiresAt = new Date(Date.now() + CODE_TTL_DAYS * 24 * 60 * 60 * 1000);

  await pool.query('INSERT INTO claim_codes (student_id, code_hash, expires_at) VALUES (?, ?, ?)', [
    studentId,
    hashCode(plainCode),
    expiresAt,
  ]);

  return plainCode;
}

/**
 * Verifies a (studentNumber, claimCode) pair without consuming the code.
 * Used by POST /api/auth/verify-claim, which the app calls before showing
 * the "complete your registration" form, and again inside register().
 *
 * @returns the active claim_codes row AND the matching student row
 */
async function verifyClaim(studentNumber, claimCode) {
  const [studentRows] = await pool.query('SELECT * FROM students WHERE student_number = ? AND active = 1', [
    studentNumber,
  ]);
  const student = studentRows[0];
  if (!student) {
    throw new ApiError(404, 'STUDENT_NOT_FOUND', 'No active student with that student number.');
  }

  // Reject up front if this student already has an account — registering
  // again must never create a second profile.
  const [existingAccountRows] = await pool.query('SELECT account_id FROM accounts WHERE student_id = ?', [
    student.student_id,
  ]);
  if (existingAccountRows.length > 0) {
    throw new ApiError(409, 'ALREADY_REGISTERED', 'This student already has an account. Please log in instead.');
  }

  const codeHash = hashCode(claimCode);
  const [claimRows] = await pool.query(
    'SELECT * FROM claim_codes WHERE student_id = ? AND code_hash = ? AND active = 1',
    [student.student_id, codeHash]
  );
  const claim = claimRows[0];
  if (!claim) {
    throw new ApiError(401, 'INVALID_CLAIM_CODE', 'That claim code does not match this student number.');
  }
  if (claim.used_at) {
    throw new ApiError(409, 'CLAIM_ALREADY_USED', 'This claim code has already been used.');
  }
  if (new Date(claim.expires_at) < new Date()) {
    throw new ApiError(410, 'CLAIM_EXPIRED', 'This claim code has expired. Ask your lecturer for a new one.');
  }

  return { student, claim };
}

/** Marks a claim code used. Call this inside the same flow as account creation. */
async function markClaimUsed(conn, claimId) {
  await conn.query('UPDATE claim_codes SET used_at = NOW(3) WHERE claim_id = ?', [claimId]);
}

module.exports = { generateClaimCode, verifyClaim, markClaimUsed, hashCode };
