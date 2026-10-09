// services/idempotency.service.js
//
// Solves Challenge 2 from the brief: "the server saves a record, the
// response is interrupted, the client retries."
//
// How it works:
//  1. The client sends a client-generated `operationId` (a UUID) with any
//     mutating request. The SAME id is reused if the client retries.
//  2. We try to INSERT IGNORE a receipt row for that id. Only the FIRST
//     request to reach the database wins that insert.
//  3. The winner runs the real effect (which manages its own transaction,
//     e.g. the group-capacity transaction in group.service.js) and then
//     stores the result on the receipt row.
//  4. Any later request with the same id finds the receipt already there
//     and simply replays the stored result instead of repeating the effect
//     — so a student is never assigned twice, a group never double-counted.
//  5. Reusing an id with a DIFFERENT request body is rejected: that would
//     hide a bug, not a legitimate retry.
//
// `operationId` is optional. Endpoints that don't pass one just run the
// effect directly — useful for simple reads or for early development.

const crypto = require('crypto');
const ApiError = require('../utils/ApiError');

function hashBody(body) {
  // A stable JSON string so the same logical request always hashes the same.
  const json = JSON.stringify(body || {}, Object.keys(body || {}).sort());
  return crypto.createHash('sha256').update(json).digest('hex');
}

/**
 * @param pool        the mysql2 promise pool
 * @param options.operationId   client-supplied UUID, or undefined
 * @param options.accountId     the authenticated account making the request
 * @param options.requestBody   the request body, used to detect id reuse
 * @param effectFn   async () => ({ statusCode, body })  — the real work.
 *                   Must throw ApiError for expected business failures.
 * @returns { replayed: boolean, statusCode: number, body: object }
 */
async function withIdempotency(pool, { operationId, accountId, requestBody }, effectFn) {
  if (!operationId) {
    const result = await effectFn();
    return { replayed: false, ...result };
  }

  const hash = hashBody(requestBody);

  const [insertResult] = await pool.query(
    'INSERT IGNORE INTO operation_receipts (operation_id, account_id, request_hash) VALUES (?, ?, ?)',
    [operationId, accountId, hash]
  );

  if (insertResult.affectedRows === 0) {
    // Someone has already claimed this operation id — this is a retry.
    const [rows] = await pool.query(
      'SELECT account_id, request_hash, status_code, response_json FROM operation_receipts WHERE operation_id = ?',
      [operationId]
    );
    const existing = rows[0];

    if (!existing || existing.account_id !== accountId || existing.request_hash !== hash) {
      throw new ApiError(
        422,
        'OPERATION_ID_REUSED',
        'This operation ID was already used for a different request.'
      );
    }
    if (existing.status_code === null) {
      // The original request is still being processed (very small window).
      throw new ApiError(409, 'CONFLICT', 'This operation is still being processed. Please retry shortly.');
    }
    return { replayed: true, statusCode: existing.status_code, body: existing.response_json };
  }

  // We won the race to claim this operation id — do the real work.
  try {
    const result = await effectFn(); // { statusCode, body }
    await pool.query('UPDATE operation_receipts SET status_code = ?, response_json = ? WHERE operation_id = ?', [
      result.statusCode,
      JSON.stringify(result.body),
      operationId,
    ]);
    return { replayed: false, ...result };
  } catch (err) {
    const statusCode = err instanceof ApiError ? err.statusCode : 500;
    const code = err instanceof ApiError ? err.code : 'INTERNAL_ERROR';
    // Best-effort: store the failure too, so a retry of a REJECTED operation
    // (e.g. GROUP_FULL) gets the same answer instead of being re-evaluated.
    await pool
      .query('UPDATE operation_receipts SET status_code = ?, response_json = ? WHERE operation_id = ?', [
        statusCode,
        JSON.stringify({ success: false, error: code, message: err.message }),
        operationId,
      ])
      .catch(() => {});
    throw err;
  }
}

module.exports = { withIdempotency, hashBody };
