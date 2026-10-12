// middleware/rateLimit.js
//
// Per-IP request limits on the endpoints an attacker would hammer. These are a
// SECOND line of defence: the real guarantees (5 tries per code, 30 seconds
// between codes, 5 failed logins then a lock) live in the database and hold
// no matter how many IP addresses an attacker uses.
//
// The counters are kept in memory, so they reset when the server restarts and
// are not shared between several server processes. That is fine for one
// server; behind a load balancer use a shared store (e.g. rate-limit-redis) and
// set `app.set('trust proxy', 1)` so the real client IP is used.

const rateLimit = require('express-rate-limit');

function makeLimiter({ windowMs = 15 * 60 * 1000, limit }) {
  // The test suite makes hundreds of auth calls from one IP; it would trip the
  // limiter immediately. tests/hardening.test.js sets RATE_LIMIT_IN_TEST to
  // exercise the real limiter.
  if (process.env.NODE_ENV === 'test' && !process.env.RATE_LIMIT_IN_TEST) {
    return (req, res, next) => next();
  }
  return rateLimit({
    windowMs,
    limit,
    standardHeaders: true,
    legacyHeaders: false,
    handler: (req, res) =>
      res.status(429).json({
        success: false,
        error: 'RATE_LIMITED',
        message: 'Too many requests. Please wait a few minutes and try again.',
      }),
  });
}

module.exports = {
  makeLimiter,
  // asking for codes (each one sends an email/SMS, so keep this tight)
  codeRequestLimiter: makeLimiter({ limit: 10 }),
  // entering codes / finishing sign-up
  codeVerifyLimiter: makeLimiter({ limit: 30 }),
  loginLimiter: makeLimiter({ limit: 30 }),
};
