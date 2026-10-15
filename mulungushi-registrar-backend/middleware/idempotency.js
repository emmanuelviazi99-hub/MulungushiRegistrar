// middleware/idempotency.js
//
// Exactly-once for the app's offline queue (Challenge 2 of the brief, for every
// endpoint that changes something).
//
// The app gives each change a UUID when the person makes it, and sends it as
//   Idempotency-Key: 6a1f6f0e-3c2b-4d0e-9a59-0d4b1f7e2c11
// every time it tries to upload that change. If the first try reached the
// server but the answer was lost (network cut, app killed), the retry finds the
// stored receipt and gets the SAME answer back, without doing the change again.
// So a queued "move to G02" or "approve" is never applied twice, and a retried
// edit is not mistaken for someone else's newer change (VERSION_CONFLICT).
//
// Uses the same operation_receipts table as /students/mutate and
// /students/:id/group (services/idempotency.service.js), which take the id in
// the body instead. Without the header a request runs normally.
//
// What is stored: the status code and JSON answer, for successes and for
// business refusals (4xx), so a retried GROUP_FULL is answered GROUP_FULL
// again. A 5xx (our fault) is NOT stored: the receipt is dropped so a retry
// runs for real.

const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const asyncHandler = require('../utils/asyncHandler');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// A receipt still unfinished after this long belongs to a request that died
// (server stopped mid-request); it is released so the change can be retried.
const ABANDONED_MS = 2 * 60 * 1000;

/** The request, reduced to what makes it "the same request": method, path and body. */
function fingerprint(req) {
  const body = req.body && typeof req.body === 'object' ? req.body : {};
  const sorted = JSON.stringify(body, Object.keys(body).sort());
  return crypto
    .createHash('sha256')
    .update(`${req.method} ${req.baseUrl}${req.path}\n${sorted}`)
    .digest('hex');
}

const parse = (json) => (typeof json === 'string' ? JSON.parse(json) : json); // MariaDB returns JSON as text

const idempotent = asyncHandler(async (req, res, next) => {
  const key = req.get('Idempotency-Key');
  if (!key) return next();
  if (!UUID.test(key)) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'Idempotency-Key: must be a UUID');
  }

  const accountId = req.user.accountId;
  const hash = fingerprint(req);

  const claim = () =>
    pool.query('INSERT IGNORE INTO operation_receipts (operation_id, account_id, request_hash) VALUES (?, ?, ?)', [
      key,
      accountId,
      hash,
    ]);

  let [claimed] = await claim();
  if (claimed.affectedRows === 0) {
    const [rows] = await pool.query(
      `SELECT account_id, request_hash, status_code, response_json,
              TIMESTAMPDIFF(MICROSECOND, created_at, NOW(3)) / 1000 AS age_ms
         FROM operation_receipts WHERE operation_id = ?`,
      [key]
    );
    const receipt = rows[0];
    if (!receipt || receipt.account_id !== accountId || receipt.request_hash !== hash) {
      throw new ApiError(422, 'OPERATION_ID_REUSED', 'This operation ID was already used for a different request.');
    }
    if (receipt.status_code !== null) {
      res.set('Idempotent-Replayed', 'true');
      res.status(receipt.status_code).json(parse(receipt.response_json));
      return undefined;
    }
    if (Number(receipt.age_ms) < ABANDONED_MS) {
      throw new ApiError(409, 'CONFLICT', 'This change is still being processed. Please retry shortly.');
    }
    // Abandoned: release it and run the request now.
    await pool.query('DELETE FROM operation_receipts WHERE operation_id = ? AND status_code IS NULL', [key]);
    [claimed] = await claim();
    if (claimed.affectedRows === 0) {
      throw new ApiError(409, 'CONFLICT', 'This change is still being processed. Please retry shortly.');
    }
  }

  // We own this key: record the answer before it is sent, whatever it is.
  const send = res.json.bind(res);
  res.json = (body) => {
    const status = res.statusCode;
    const store =
      status >= 500
        ? pool.query('DELETE FROM operation_receipts WHERE operation_id = ?', [key])
        : pool.query('UPDATE operation_receipts SET status_code = ?, response_json = ? WHERE operation_id = ?', [
            status,
            JSON.stringify(body),
            key,
          ]);
    store.then(
      () => send(body),
      () => send(body)
    );
    return res;
  };
  return next();
});

module.exports = { idempotent, fingerprint };
