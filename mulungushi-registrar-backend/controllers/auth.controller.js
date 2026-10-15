// controllers/auth.controller.js
//
// Sign-up (students and lecturers), password reset, login and logout.
//
// Sign-up is a three step conversation, the same shape the Android app's
// AuthService expects:
//   1. request   — the details are checked, and a verification code is sent to
//                  their email (and phone, if SMS delivery is switched on)
//   2. verify    — they type the code (10 minutes, 5 tries)
//   3. complete  — they choose a password; the account is created
//
// Who may sign up:
//   student   anyone, for a student number that is not on the roster yet: the
//             student row is created when the emailed code is confirmed.
//             If a lecturer already added that number, the NRC + date of birth
//             must match that row. (The number alone is not secret enough — it
//             is printed on timetables — so the NRC and birth date are the proof
//             that the record is yours.) An NRC can only belong to one student.
//   lecturer  email + employee ID must be on staff_roster, which only the server
//             operator can edit. A lecturer account can edit the roster, so it
//             must never be creatable by simply knowing an email address.

const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const codeService = require('../services/code.service');
const delivery = require('../services/delivery.service');
const accountService = require('../services/account.service');
const studentService = require('../services/student.service');
const { withTransaction } = require('../utils/transaction');
const { serializeAccount, serializeStudent } = require('../utils/serialize');
const { maskEmail } = require('../utils/validators');
const { bcryptRounds } = require('../config/env');

const MAX_FAILED_LOGINS = 5;
const LOCK_MINUTES = 15;

// Same message for every login failure (unknown person, wrong password, locked
// account, disabled account) so the response never says which accounts exist.
const LOGIN_FAILED_MESSAGE =
  'Incorrect student number/email or password. After 5 wrong attempts the account is locked for 15 minutes.';

// Compared against when no account matches, so a login for an unknown user
// takes as long as one for a real user (bcrypt is deliberately slow).
const DUMMY_HASH = bcrypt.hashSync('not-a-real-password', bcryptRounds());

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

function signToken(account) {
  return jwt.sign({ accountId: account.account_id, role: account.role }, process.env.JWT_SECRET, {
    expiresIn: process.env.JWT_EXPIRES_IN || '12h',
  });
}

async function sessionBody(accountId) {
  const profile = await accountService.loadProfile(accountId);
  const body = { success: true, token: signToken(profile), account: serializeAccount(profile) };
  if (profile.student_id) {
    body.student = serializeStudent(await studentService.findStudent(profile.student_id, { includeDeleted: true }), {
      full: true,
    });
  }
  return body;
}

/** Sends the code; if delivery fails the code is discarded so it never counts as "sent". */
async function deliverOrDiscard({ codeId, email, phone, code, purpose }) {
  try {
    await delivery.sendCode({ email, phone, code, purpose });
  } catch (err) {
    await codeService.discard(codeId);
    console.error(`Could not deliver a ${purpose} code: ${err.message}`); // eslint-disable-line no-console
    throw new ApiError(502, 'DELIVERY_FAILED', 'We could not send the code right now. Please try again shortly.');
  }
}

function codeSentBody(email, issued) {
  return {
    success: true,
    status: 'OK',
    maskedEmail: maskEmail(email),
    expiresInSeconds: issued.expiresInSeconds,
    resendAfterSeconds: issued.resendAfterSeconds,
  };
}

/** Maps the unique-key violations an account INSERT (or a self sign-up's student INSERT) can raise. */
function translateAccountDuplicate(err) {
  // Someone else finished signing up with this student number or NRC between our check and now.
  if (err instanceof ApiError && (err.code === 'DUPLICATE_STUDENT_NUMBER' || err.code === 'DUPLICATE_NRC')) {
    return new ApiError(409, 'ALREADY_REGISTERED', 'This student number or NRC has just been registered. Please sign in, or ask your lecturer.');
  }
  if (err && err.code === 'ER_DUP_ENTRY') {
    const msg = String(err.sqlMessage || '');
    if (msg.includes('uq_account_email')) return new ApiError(409, 'EMAIL_IN_USE', 'That email address is already used by another account.');
    if (msg.includes('uq_account_student') || msg.includes('uq_account_staff')) {
      return new ApiError(409, 'ALREADY_REGISTERED', 'This person already has an account. Please sign in.');
    }
  }
  return err;
}

/**
 * Step 3 for both roles: the person proved they hold the code, now the account
 * is created. Using up the code is the gate — if two requests race to finish
 * with the same code, exactly one flips consumed_at and the other stops.
 *
 * The verification code row already records WHO is signing up (student_id for
 * a roster student, staff_id for a lecturer), so it is copied straight onto the
 * account; the account's role is then derived by the database from that link.
 *
 * @param linkStudent optional (conn, codeRow) => student_id, run in the same
 *        transaction before the account is inserted. Student sign-up uses it to
 *        create the student row (self sign-up) or update the chosen programme.
 *        If anything fails, nothing is kept: not the student, not the account,
 *        and the code is not used up.
 * @returns the new account_id
 */
async function createAccountFromCode({ purpose, email, password, linkStudent }) {
  const verified = await codeService.requireVerified({ purpose, email });
  const passwordHash = await bcrypt.hash(password, bcryptRounds());
  try {
    return await withTransaction(async (conn) => {
      if (!(await codeService.consume(conn, verified.code_id))) {
        throw new ApiError(403, 'NOT_VERIFIED', 'Please verify your code first.');
      }
      // A student sign-up may first create the student row (self sign-up of a new number).
      const studentId = linkStudent ? await linkStudent(conn, verified) : verified.student_id;
      const [result] = await conn.query(
        'INSERT INTO accounts (email, phone, password_hash, student_id, staff_id) VALUES (?, ?, ?, ?, ?)',
        [email, verified.phone, passwordHash, studentId, verified.staff_id]
      );
      return result.insertId;
    });
  } catch (err) {
    throw translateAccountDuplicate(studentService.translateDuplicate(err));
  }
}

// ---------------------------------------------------------------------------
// student sign-up
// ---------------------------------------------------------------------------

async function studentRegisterRequest(req, res) {
  const { fullName, studentNumber, nrc, dateOfBirth, email, phone } = req.body;

  const notOnRecord = () =>
    new ApiError(
      404,
      'NOT_ON_ROSTER',
      'That student number or NRC is already on the university record with different details. Check them, or ask your lecturer.'
    );

  // Is this student number already on the roster (added by a lecturer, or signed up before)?
  // A soft-deleted student's number stays reserved, so it counts too.
  const [byNumber] = await pool.query(
    `SELECT student_id, (nrc = ? AND date_of_birth = ? AND active = 1) AS matches
       FROM students WHERE student_number = ?`,
    [nrc, dateOfBirth, studentNumber]
  );

  let studentId = null;
  let newStudent = null;
  if (byNumber.length > 0) {
    // On the roster: the NRC and birth date must prove the record is theirs. One
    // answer for every mismatch, so it never says which detail was wrong.
    if (!byNumber[0].matches) throw notOnRecord();
    studentId = byNumber[0].student_id;
    const [existing] = await pool.query('SELECT account_id FROM accounts WHERE student_id = ?', [studentId]);
    if (existing.length > 0) {
      throw new ApiError(409, 'ALREADY_REGISTERED', 'This student already has an account. Please sign in.');
    }
  } else {
    // A new student. Their NRC must not already belong to another student.
    const [byNrc] = await pool.query('SELECT student_id FROM students WHERE nrc = ?', [nrc]);
    if (byNrc.length > 0) throw notOnRecord();
    // Nothing is written to students yet: the details wait on the code row and the
    // student is created only when the emailed code has been confirmed.
    newStudent = { fullName, studentNumber, nrc, dateOfBirth };
  }

  const [emailUsed] = await pool.query('SELECT account_id FROM accounts WHERE email = ?', [email]);
  if (emailUsed.length > 0) {
    throw new ApiError(409, 'EMAIL_IN_USE', 'That email address is already used by another account.');
  }

  const issued = await codeService.issue({ purpose: 'STUDENT_SIGNUP', email, studentId, newStudent, phone });
  await deliverOrDiscard({ codeId: issued.codeId, email, phone, code: issued.code, purpose: 'STUDENT_SIGNUP' });
  res.json(codeSentBody(email, issued));
}

async function studentRegisterVerify(req, res) {
  const { email, code } = req.body;
  const row = await codeService.verify({ purpose: 'STUDENT_SIGNUP', email, code });
  const pending = codeService.newStudentOf(row);
  let fullName = pending ? pending.fullName : null;
  if (!pending) {
    const [[student]] = await pool.query('SELECT full_name FROM students WHERE student_id = ?', [row.student_id]);
    fullName = student ? student.full_name : null;
  }
  res.json({ success: true, status: 'OK', fullName });
}

async function studentRegisterComplete(req, res) {
  const { email, password, programmeCode } = req.body;
  // The student chooses their programme at sign-up (for a roster student the roster value is only a default).
  const programmeId = programmeCode ? await studentService.programmeIdFor(pool, programmeCode) : null;

  const accountId = await createAccountFromCode({
    purpose: 'STUDENT_SIGNUP',
    email,
    password,
    linkStudent: async (conn, verified) => {
      const pending = codeService.newStudentOf(verified);
      if (pending) {
        // Self sign-up: the email is confirmed, so now the student row is created
        // (Unassigned; a lecturer places them in a lab group).
        if (!programmeCode) throw new ApiError(422, 'VALIDATION_ERROR', 'programmeCode: choose your programme');
        return studentService.insertStudent(conn, { ...pending, programmeCode });
      }
      if (programmeId !== null) {
        await conn.query(
          'UPDATE students SET programme_id = ?, version = version + 1 WHERE student_id = ? AND programme_id <> ?',
          [programmeId, verified.student_id, programmeId]
        );
      }
      return verified.student_id;
    },
  });
  res.status(201).json(await sessionBody(accountId));
}

// ---------------------------------------------------------------------------
// lecturer sign-up
// ---------------------------------------------------------------------------

async function staffRegisterRequest(req, res) {
  const { email, employeeId, phone } = req.body;

  const [staff] = await pool.query(
    'SELECT staff_id FROM staff_roster WHERE email = ? AND employee_id = ? AND active = 1',
    [email, employeeId]
  );
  if (staff.length === 0) {
    throw new ApiError(
      404,
      'NOT_ON_ROSTER',
      'We could not match that email and employee ID to the staff list. Ask the registrar to add you.'
    );
  }
  const staffId = staff[0].staff_id;

  const [existing] = await pool.query('SELECT account_id FROM accounts WHERE staff_id = ?', [staffId]);
  if (existing.length > 0) {
    throw new ApiError(409, 'ALREADY_REGISTERED', 'This staff member already has an account. Please sign in.');
  }

  // The code goes to the email address ON THE STAFF LIST (which is the one the
  // request matched), so only someone who can read that mailbox can continue.
  const issued = await codeService.issue({ purpose: 'STAFF_SIGNUP', email, staffId, phone: phone || null });
  await deliverOrDiscard({ codeId: issued.codeId, email, phone: phone || null, code: issued.code, purpose: 'STAFF_SIGNUP' });
  res.json(codeSentBody(email, issued));
}

async function staffRegisterVerify(req, res) {
  const { email, code } = req.body;
  const row = await codeService.verify({ purpose: 'STAFF_SIGNUP', email, code });
  const [[staff]] = await pool.query('SELECT full_name FROM staff_roster WHERE staff_id = ?', [row.staff_id]);
  res.json({ success: true, status: 'OK', fullName: staff ? staff.full_name : null });
}

async function staffRegisterComplete(req, res) {
  const { email, password } = req.body;
  const accountId = await createAccountFromCode({ purpose: 'STAFF_SIGNUP', email, password });
  res.status(201).json(await sessionBody(accountId));
}

// ---------------------------------------------------------------------------
// forgotten password
// ---------------------------------------------------------------------------

async function passwordResetRequest(req, res) {
  const { email } = req.body;

  const [accounts] = await pool.query('SELECT account_id, phone FROM accounts WHERE email = ? AND active = 1', [email]);
  if (accounts.length > 0) {
    try {
      const issued = await codeService.issue({
        purpose: 'PASSWORD_RESET',
        email,
        accountId: accounts[0].account_id,
        phone: accounts[0].phone,
      });
      await deliverOrDiscard({ codeId: issued.codeId, email, phone: accounts[0].phone, code: issued.code, purpose: 'PASSWORD_RESET' });
    } catch (err) {
      // Swallow EVERY failure here: the reply must be identical whether or not
      // the email has an account, or this endpoint would reveal who is registered.
      if (!(err instanceof ApiError && err.code === 'RESEND_TOO_SOON')) {
        console.error(`Password reset request failed: ${err.message}`); // eslint-disable-line no-console
      }
    }
  }
  res.json({
    success: true,
    status: 'OK',
    maskedEmail: maskEmail(email),
    expiresInSeconds: codeService.CODE_TTL_SECONDS,
    resendAfterSeconds: codeService.RESEND_COOLDOWN_SECONDS,
  });
}

async function passwordResetVerify(req, res) {
  const { email, code } = req.body;
  await codeService.verify({ purpose: 'PASSWORD_RESET', email, code });
  res.json({ success: true, status: 'OK' });
}

async function passwordReset(req, res) {
  const { email, code, newPassword } = req.body;
  const row = await codeService.verify({ purpose: 'PASSWORD_RESET', email, code });

  const passwordHash = await bcrypt.hash(newPassword, bcryptRounds());
  await withTransaction(async (conn) => {
    if (!(await codeService.consume(conn, row.code_id))) {
      throw new ApiError(410, 'CODE_EXPIRED', 'That code has expired. Please ask for a new one.');
    }
    // Also clears any login lock, and stamps password_changed_at so every token
    // issued before now stops working (see middleware/auth.js).
    await conn.query(
      `UPDATE accounts
          SET password_hash = ?, failed_logins = 0, locked_until = NULL, password_changed_at = NOW(3)
        WHERE account_id = ?`,
      [passwordHash, row.account_id]
    );
  });
  res.json({ success: true, status: 'OK' });
}

// ---------------------------------------------------------------------------
// login / logout
// ---------------------------------------------------------------------------

async function recordFailedLogin(accountId) {
  // MySQL evaluates the SET list left to right and both expressions read the
  // OLD failed_logins (it is only assigned in the second one), so the 5th
  // failure sets the lock and resets the counter in a single atomic statement.
  await pool.query(
    `UPDATE accounts
        SET locked_until  = IF(failed_logins + 1 >= ?, DATE_ADD(NOW(3), INTERVAL ? MINUTE), locked_until),
            failed_logins = IF(failed_logins + 1 >= ?, 0, failed_logins + 1)
      WHERE account_id = ?`,
    [MAX_FAILED_LOGINS, LOCK_MINUTES, MAX_FAILED_LOGINS, accountId]
  );
}

async function login(req, res) {
  const { loginKey, password } = req.body;
  const key = String(loginKey).trim();

  let rows = [];
  if (key.includes('@')) {
    [rows] = await pool.query(
      'SELECT a.*, (a.locked_until IS NOT NULL AND a.locked_until > NOW(3)) AS is_locked FROM accounts a WHERE a.email = ?',
      [key.toLowerCase()]
    );
  } else if (/^\d{9}$/.test(key)) {
    [rows] = await pool.query(
      `SELECT a.*, (a.locked_until IS NOT NULL AND a.locked_until > NOW(3)) AS is_locked
         FROM accounts a JOIN students s ON s.student_id = a.student_id
        WHERE s.student_number = ?`,
      [key]
    );
  }
  const account = rows[0];

  const passwordOk = await bcrypt.compare(password, account ? account.password_hash : DUMMY_HASH);

  if (!account || !account.active || account.is_locked || !passwordOk) {
    // Only count a wrong password against an account that is open to guessing.
    if (account && account.active && !account.is_locked && !passwordOk) await recordFailedLogin(account.account_id);
    throw new ApiError(401, 'INVALID_CREDENTIALS', LOGIN_FAILED_MESSAGE);
  }

  if (account.failed_logins > 0 || account.locked_until) {
    await pool.query('UPDATE accounts SET failed_logins = 0, locked_until = NULL WHERE account_id = ?', [account.account_id]);
  }
  res.json(await sessionBody(account.account_id));
}

// PUT /api/auth/password (signed in) — "Change password" in Edit my details.
// The current password must be right; wrong guesses count towards the same
// 5-strikes lock as sign-in, so this cannot be used to guess a password on an
// unlocked phone. Changing it stamps password_changed_at, which signs out every
// other phone (their tokens are older); this phone gets a fresh token back.
async function changePassword(req, res) {
  const { currentPassword, newPassword } = req.body;
  const [rows] = await pool.query(
    `SELECT account_id, password_hash, (locked_until IS NOT NULL AND locked_until > NOW(3)) AS is_locked
       FROM accounts WHERE account_id = ?`,
    [req.user.accountId]
  );
  const account = rows[0];
  const ok = await bcrypt.compare(currentPassword, account.password_hash);
  if (account.is_locked || !ok) {
    if (!account.is_locked) await recordFailedLogin(account.account_id);
    throw new ApiError(400, 'WRONG_PASSWORD', "Your current password isn't right.");
  }
  if (currentPassword === newPassword) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'newPassword: must be different from your current password');
  }
  const passwordHash = await bcrypt.hash(newPassword, bcryptRounds());
  await pool.query(
    `UPDATE accounts
        SET password_hash = ?, failed_logins = 0, locked_until = NULL, password_changed_at = NOW(3)
      WHERE account_id = ?`,
    [passwordHash, account.account_id]
  );
  res.json(await sessionBody(account.account_id));
}

// Tokens are stateless JWTs, so there is nothing to revoke server-side; the app
// discards its copy. (A password reset DOES invalidate older tokens.)
async function logout(req, res) {
  res.json({ success: true, status: 'OK' });
}

module.exports = {
  studentRegisterRequest,
  studentRegisterVerify,
  studentRegisterComplete,
  staffRegisterRequest,
  staffRegisterVerify,
  staffRegisterComplete,
  passwordResetRequest,
  passwordResetVerify,
  passwordReset,
  login,
  logout,
  changePassword,
};
