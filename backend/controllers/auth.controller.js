// controllers/auth.controller.js

const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const otpService = require('../services/otp.service');
const { serializeAccount } = require('../utils/serialize');

const BCRYPT_ROUNDS = 12;

function signToken(account) {
  return jwt.sign({ accountId: account.account_id, role: account.role }, process.env.JWT_SECRET, {
    expiresIn: process.env.JWT_EXPIRES_IN || '12h',
  });
}

// POST /api/auth/send-otp
async function sendOtp(req, res) {
  const { email } = req.body;
  if (!email) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'Email is required.');
  }

  await otpService.requestOtp(email);
  res.json({ success: true, message: 'Verification code sent to email.' });
}

// POST /api/auth/register-with-otp
async function registerWithOtp(req, res) {
  const { fullName, studentNumber, email, dateOfBirth, nrc, programmeCode, otpCode, password, username } = req.body;

  if (!fullName || !studentNumber || !email || !nrc || !programmeCode || !otpCode || !password || !username) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'All required registration fields must be provided.');
  }

  // 1. Verify OTP
  await otpService.verifyOtp(email, otpCode);

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();

    // 2. Check if student number, email, or NRC already exists
    const [existing] = await conn.query(
      'SELECT student_id FROM students WHERE student_number = ? OR email = ? OR nrc = ?',
      [studentNumber.trim(), email.trim(), nrc.trim()]
    );
    if (existing.length > 0) {
      throw new ApiError(409, 'DUPLICATE_STUDENT', 'Student number, email, or NRC is already registered.');
    }

    // 3. Check if username is taken
    const [existingUsername] = await conn.query('SELECT account_id FROM accounts WHERE username = ?', [
      username.trim(),
    ]);
    if (existingUsername.length > 0) {
      throw new ApiError(409, 'USERNAME_TAKEN', 'That username is already in use.');
    }

    // 4. Resolve programme ID
    const [progRows] = await conn.query('SELECT programme_id FROM programmes WHERE code = ?', [programmeCode.toUpperCase().trim()]);
    const programme = progRows[0];
    if (!programme) {
      throw new ApiError(400, 'INVALID_PROGRAMME', `Unknown programme code "${programmeCode}".`);
    }

    // 5. Find an available lab group with < 15 members
    const [groups] = await conn.query(
      `SELECT g.group_id, COUNT(s.student_id) AS activeCount
       FROM lab_groups g
       LEFT JOIN students s ON s.group_id = g.group_id AND s.active = 1
       WHERE g.active = 1
       GROUP BY g.group_id
       HAVING activeCount < g.capacity
       ORDER BY activeCount ASC FOR UPDATE`
    );
    const assignedGroupId = groups.length > 0 ? groups[0].group_id : null;

    // 6. Insert student record
    const studentId = crypto.randomUUID();
    await conn.query(
      'INSERT INTO students (student_id, student_number, full_name, email, date_of_birth, nrc, programme_id, group_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
      [
        studentId,
        studentNumber.trim(),
        fullName.trim(),
        email.trim().toLowerCase(),
        dateOfBirth || null,
        nrc.trim(),
        programme.programme_id,
        assignedGroupId,
      ]
    );

    // 7. Hash password and create account
    const passwordHash = await bcrypt.hash(password, BCRYPT_ROUNDS);
    const [insertResult] = await conn.query(
      'INSERT INTO accounts (username, password_hash, role, student_id) VALUES (?, ?, ?, ?)',
      [username.trim(), passwordHash, 'STUDENT', studentId]
    );

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

// POST /api/auth/register-lecturer
async function registerLecturer(req, res) {
  const { fullName, email, employeeId, username, password } = req.body;

  if (!email || !employeeId || !username || !password) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'All required lecturer fields must be provided.');
  }

  const [existing] = await connQuery('SELECT account_id FROM accounts WHERE username = ? OR employee_id = ?', [
    username.trim(),
    employeeId.trim(),
  ]);
  if (existing.length > 0) {
    throw new ApiError(409, 'USERNAME_TAKEN', 'Username or Employee ID is already registered.');
  }

  const passwordHash = await bcrypt.hash(password, BCRYPT_ROUNDS);

  const [insertResult] = await pool.query(
    'INSERT INTO accounts (username, password_hash, role, employee_id) VALUES (?, ?, ?, ?)',
    [username.trim(), passwordHash, 'LECTURER', employeeId.trim()]
  );

  const [accountRows] = await pool.query('SELECT * FROM accounts WHERE account_id = ?', [insertResult.insertId]);
  const account = accountRows[0];

  res.status(201).json({
    success: true,
    token: signToken(account),
    ...serializeAccount(account),
  });
}

async function connQuery(sql, params) {
  return await pool.query(sql, params);
}

// POST /api/auth/login
async function login(req, res) {
  const { username, password } = req.body;

  const [rows] = await pool.query(
    'SELECT a.*, s.email, s.student_number FROM accounts a LEFT JOIN students s ON a.student_id = s.student_id WHERE a.username = ? OR s.email = ? OR s.student_number = ? OR a.employee_id = ?',
    [username.trim(), username.trim(), username.trim(), username.trim()]
  );
  const account = rows[0];

  if (!account || !account.active) {
    throw new ApiError(401, 'INVALID_CREDENTIALS', 'Incorrect username, email, student number or password.');
  }

  const passwordOk = await bcrypt.compare(password, account.password_hash);
  if (!passwordOk) {
    throw new ApiError(401, 'INVALID_CREDENTIALS', 'Incorrect username, email, student number or password.');
  }

  res.json({
    success: true,
    token: signToken(account),
    ...serializeAccount(account),
  });
}

// POST /api/auth/logout
async function logout(req, res) {
  res.json({ success: true, message: 'Logged out. Discard the token on the client.' });
}

module.exports = { sendOtp, registerWithOtp, registerLecturer, login, logout };
