// controllers/auth.controller.js

const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const claimService = require('../services/claim.service');
const { serializeAccount } = require('../utils/serialize');

const BCRYPT_ROUNDS = 12;

function signToken(account) {
  return jwt.sign({ accountId: account.account_id, role: account.role }, process.env.JWT_SECRET, {
    expiresIn: process.env.JWT_EXPIRES_IN || '12h',
  });
}

// POST /api/auth/verify-claim
// Lets the app check a (studentNumber, claimCode) pair BEFORE showing the
// "finish your registration" form, so the student gets fast feedback.
// Does not create anything and does not consume the code.
async function verifyClaim(req, res) {
  const { studentNumber, claimCode } = req.body;
  const { student } = await claimService.verifyClaim(studentNumber.trim(), claimCode.trim());

  res.json({
    success: true,
    studentId: student.student_id,
    fullName: student.full_name,
  });
}

// POST /api/auth/register
// Re-verifies the claim (never trust a client-side "I already checked"),
// then creates the account AND consumes the claim code in one transaction,
// so a crash between the two steps can't leave a usable-twice code.
async function register(req, res) {
  const { studentNumber, claimCode, username, password } = req.body;

  const { student, claim } = await claimService.verifyClaim(studentNumber.trim(), claimCode.trim());

  const [existingUsername] = await pool.query('SELECT account_id FROM accounts WHERE username = ?', [
    username.trim(),
  ]);
  if (existingUsername.length > 0) {
    throw new ApiError(409, 'USERNAME_TAKEN', 'That username is already in use.');
  }

  const passwordHash = await bcrypt.hash(password, BCRYPT_ROUNDS);

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();

    const [insertResult] = await conn.query(
      'INSERT INTO accounts (username, password_hash, role, student_id) VALUES (?, ?, ?, ?)',
      [username.trim(), passwordHash, 'STUDENT', student.student_id]
    );
    await claimService.markClaimUsed(conn, claim.claim_id);

    await conn.commit();

    const [accountRows] = await pool.query('SELECT * FROM accounts WHERE account_id = ?', [insertResult.insertId]);
    const account = accountRows[0];

    res.status(201).json({
      success: true,
      token: signToken(account),
      ...serializeAccount(account),
    });
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
}

// POST /api/auth/login
async function login(req, res) {
  const { username, password } = req.body;

  const [rows] = await pool.query('SELECT * FROM accounts WHERE username = ?', [username.trim()]);
  const account = rows[0];

  // Same error for "no such user" and "wrong password" — do not reveal
  // which one it was, that would let an attacker enumerate usernames.
  if (!account || !account.active) {
    throw new ApiError(401, 'INVALID_CREDENTIALS', 'Incorrect username or password.');
  }

  const passwordOk = await bcrypt.compare(password, account.password_hash);
  if (!passwordOk) {
    throw new ApiError(401, 'INVALID_CREDENTIALS', 'Incorrect username or password.');
  }

  res.json({
    success: true,
    token: signToken(account),
    ...serializeAccount(account),
  });
}

// POST /api/auth/logout
// Tokens are stateless JWTs, so there's nothing to delete server-side for
// this academic-project scope; the client is responsible for discarding
// the token (and, per the brief, clearing/locking local account data).
// Kept as a real endpoint so the Android app has a single place to call
// and so a future token-blacklist table can slot in here without the
// client changing anything.
async function logout(req, res) {
  res.json({ success: true, message: 'Logged out. Discard the token on the client.' });
}

module.exports = { verifyClaim, register, login, logout };
