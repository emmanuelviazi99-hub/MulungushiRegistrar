// middleware/role.js
//
// Use AFTER requireAuth. Rejects the request unless req.user.role is one
// of the allowed roles. This is the server-side enforcement the brief
// insists on: "hiding buttons alone is insufficient" (section 20).
//
// Usage:
//   router.get('/students', requireAuth, requireRole('LECTURER'), ...)

const ApiError = require('../utils/ApiError');

function requireRole(...allowedRoles) {
  return function (req, res, next) {
    if (!req.user || !allowedRoles.includes(req.user.role)) {
      throw new ApiError(403, 'FORBIDDEN', 'You do not have permission to do this.');
    }
    next();
  };
}

module.exports = { requireRole };
