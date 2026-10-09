// utils/ApiError.js
//
// A small helper so every part of the app can throw one consistent error
// shape. The errorHandler middleware (middleware/errorHandler.js) catches
// these and turns them into the JSON format described in the API docs:
//   { "success": false, "error": "GROUP_FULL", "message": "..." }

class ApiError extends Error {
  /**
   * @param {number} statusCode  HTTP status code, e.g. 404, 409
   * @param {string} code        Short machine-readable code, e.g. "GROUP_FULL"
   * @param {string} [message]   Human-readable message. Defaults to `code`.
   */
  constructor(statusCode, code, message) {
    super(message || code);
    this.statusCode = statusCode;
    this.code = code;
  }
}

module.exports = ApiError;
