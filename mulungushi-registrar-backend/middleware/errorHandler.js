// middleware/errorHandler.js
//
// The single place that turns any thrown error into the JSON shape every
// client response uses:
//   { "success": false, "error": "GROUP_FULL", "message": "..." }
//
// Must be registered LAST in server.js, after all routes.

const ApiError = require('../utils/ApiError');

function errorHandler(err, req, res, next) { // eslint-disable-line no-unused-vars
  if (err instanceof ApiError) {
    return res.status(err.statusCode).json({
      success: false,
      error: err.code,
      message: err.message,
    });
  }

  // MySQL duplicate-key errors that slip past our own uniqueness checks
  // (e.g. a race between two inserts) still get a clean 409 instead of a
  // raw stack trace reaching the client.
  if (err && err.code === 'ER_DUP_ENTRY') {
    return res.status(409).json({
      success: false,
      error: 'CONFLICT',
      message: 'That value is already in use.',
    });
  }

  // Anything else is a bug — log the full error on the server, but never
  // leak internals (stack traces, SQL, file paths) to the client.
  console.error(err); // eslint-disable-line no-console
  return res.status(500).json({
    success: false,
    error: 'INTERNAL_ERROR',
    message: 'Something went wrong. Please try again.',
  });
}

module.exports = errorHandler;
