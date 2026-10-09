// middleware/auth.js
//
// Checks the Authorization header on every protected route:
//   Authorization: Bearer <token>
//
// On success it attaches `req.user = { accountId, role, studentId }` so
// every controller downstream knows WHO is making the request and in WHAT
// role — this is what section 20 of the brief/prompt means by "the backend
// must check identity, role and ownership on every protected request".
//
// Importantly: we re-check the account is still active in the database,
// not just that the token is well-formed. A disabled account (e.g. after
// a soft-deleted student) must not keep working just because its old
// token hasn't expired yet.

const jwt = require('jsonwebtoken');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const asyncHandler = require('../utils/asyncHandler');

const requireAuth = asyncHandler(async (req, res, next) => {
  const header = req.headers.authorization || '';
  const [scheme, token] = header.split(' ');

  if (scheme !== 'Bearer' || !token) {
    throw new ApiError(401, 'UNAUTHORIZED', 'Missing or malformed Authorization header.');
  }

  let payload;
  try {
    payload = jwt.verify(token, process.env.JWT_SECRET);
  } catch (err) {
    throw new ApiError(401, 'UNAUTHORIZED', 'Invalid or expired token.');
  }

  const [rows] = await pool.query(
    'SELECT account_id, role, student_id, active FROM accounts WHERE account_id = ?',
    [payload.accountId]
  );
  const account = rows[0];

  if (!account || !account.active) {
    throw new ApiError(401, 'UNAUTHORIZED', 'This account is no longer active.');
  }

  req.user = {
    accountId: account.account_id,
    role: account.role,
    studentId: account.student_id, // null for lecturers
  };

  next();
});

module.exports = { requireAuth };
