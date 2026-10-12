// utils/ApiError.js
//
// A small helper so every part of the app can throw one consistent error
// shape. The errorHandler middleware (middleware/errorHandler.js) catches
// these and turns them into the JSON format described in the API docs:
//   { "success": false, "error": "GROUP_FULL", "message": "..." }
//
// `extra` lets an error carry machine-readable details the app needs, e.g.
//   BAD_CODE            -> { triesLeft: 3 }
//   RESEND_TOO_SOON     -> { retryAfterSeconds: 21 }

class ApiError extends Error {
  /**
   * @param {number} statusCode  HTTP status code, e.g. 404, 409
   * @param {string} code        Short machine-readable code, e.g. "GROUP_FULL"
   * @param {string} [message]   Human-readable message. Defaults to `code`.
   * @param {object} [extra]     Extra JSON fields merged into the response body.
   */
  constructor(statusCode, code, message, extra) {
    super(message || code);
    this.statusCode = statusCode;
    this.code = code;
    this.extra = extra || null;
  }
}

module.exports = ApiError;
