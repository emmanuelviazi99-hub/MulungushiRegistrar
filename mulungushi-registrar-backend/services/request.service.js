// services/request.service.js
//
// Change requests: a student PROPOSES, a lecturer approves or declines.
// The student's record does not change until approval — matching the brief:
// "An offline request is Pending until confirmed by the server."
//
// Both kinds share one table (change_requests) and one implementation:
//   GROUP   move the student to another lab group (or Unassigned)
//   NUMBER  correct the student number (identity, student_id, never changes)
//
// "One open request per student per kind" is a UNIQUE key in the database
// (see schema.sql), not a SELECT-then-INSERT check that two taps could race.

const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const { withTransaction } = require('../utils/transaction');
const groupService = require('./group.service');
const v = require('../utils/validators');

const KINDS = {
  GROUP: { label: 'group-change' },
  NUMBER: { label: 'number-correction' },
};

const REQUEST_VIEW_SQL = `
  SELECT r.*, s.full_name, s.student_number,
         fg.group_code AS from_group_code, tg.group_code AS to_group_code
    FROM change_requests r
    JOIN students s         ON s.student_id = r.student_id
    LEFT JOIN lab_groups fg ON fg.group_id  = r.from_group_id
    LEFT JOIN lab_groups tg ON tg.group_id  = r.to_group_id`;

const notFound = () => new ApiError(404, 'REQUEST_NOT_FOUND', 'No pending request with that id.');

/**
 * A student opens a request. The group / number they have NOW is stored with
 * it (from_group_id / old_number), because after approval the student row no
 * longer shows what it was.
 *
 * @param details GROUP: { groupCode, reason }   NUMBER: { newNumber, reason }
 * @returns the new request_id
 */
async function createRequest(kind, studentId, details) {
  const reason = details.reason ? String(details.reason).trim().slice(0, 255) || null : null;
  let sql;
  let params;

  if (kind === 'GROUP') {
    const target = await groupService.resolveGroupId(pool, details.groupCode); // null = Unassigned
    // Checked against the student's group as it is NOW, in the same statement:
    // asking for the group you are already in inserts nothing (ALREADY_IN_GROUP below).
    // An unassigned student's request (from_group_id NULL) is their first group;
    // an assigned student's is a group change. Same kind, same approval flow.
    sql = `INSERT INTO change_requests (kind, student_id, from_group_id, to_group_id, reason)
           SELECT 'GROUP', student_id, group_id, ?, ? FROM students
            WHERE student_id = ? AND active = 1 AND NOT (group_id <=> ?)`;
    params = [target ? target.group_id : null, reason, studentId, target ? target.group_id : null];
  } else {
    const newNumber = String(details.newNumber ?? '').trim();
    if (!v.isValidStudentNumber(newNumber)) {
      throw new ApiError(422, 'VALIDATION_ERROR', 'newNumber: must be exactly 9 digits, no spaces');
    }
    sql = `INSERT INTO change_requests (kind, student_id, old_number, new_number, reason)
           SELECT 'NUMBER', student_id, student_number, ?, ? FROM students WHERE student_id = ? AND active = 1`;
    params = [newNumber, reason, studentId];
  }

  let result;
  try {
    [result] = await pool.query(sql, params);
  } catch (err) {
    if (err.code === 'ER_DUP_ENTRY' && String(err.sqlMessage).includes('uq_creq_one_pending')) {
      throw new ApiError(409, 'REQUEST_ALREADY_PENDING', `You already have an open ${KINDS[kind].label} request.`);
    }
    throw err;
  }
  if (result.affectedRows === 0) {
    if (kind === 'GROUP') {
      const [[me]] = await pool.query('SELECT student_id FROM students WHERE student_id = ? AND active = 1', [studentId]);
      if (me) throw new ApiError(409, 'ALREADY_IN_GROUP', 'You are already in that group.');
    }
    throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Your profile could not be found.');
  }
  return result.insertId;
}

/**
 * The lecturer's list for one kind. PENDING (the queue) is oldest first.
 * ANSWERED is every approved or declined request, newest first (the history
 * tab), capped at the latest 200.
 */
async function listRequests(kind, status = 'PENDING') {
  const wanted = String(status).toUpperCase();
  if (wanted === 'ANSWERED') {
    const [rows] = await pool.query(
      `${REQUEST_VIEW_SQL} WHERE r.kind = ? AND r.status <> 'PENDING' ORDER BY r.resolved_at DESC, r.request_id DESC LIMIT 200`,
      [kind]
    );
    return rows;
  }
  const [rows] = await pool.query(`${REQUEST_VIEW_SQL} WHERE r.kind = ? AND r.status = ? ORDER BY r.requested_at ASC`, [
    kind,
    wanted,
  ]);
  return rows;
}

/**
 * A student's own latest request of each kind (pending or answered), so the
 * app can show "Pending" or tell them it was approved / declined.
 * @returns { GROUP: row|null, NUMBER: row|null }
 */
async function latestForStudent(studentId) {
  const [rows] = await pool.query(
    `${REQUEST_VIEW_SQL}
      WHERE r.request_id IN (SELECT MAX(request_id) FROM change_requests WHERE student_id = ? GROUP BY kind)`,
    [studentId]
  );
  const out = { GROUP: null, NUMBER: null };
  for (const row of rows) out[row.kind] = row;
  return out;
}

/**
 * A student withdraws their own open request before a lecturer answers it.
 * The row is removed (a withdrawn request is not history worth keeping), which
 * also frees the "one open request per kind" slot. Only PENDING rows of this
 * student can be removed, so an answered request can never be erased.
 */
async function cancelOwn(kind, studentId) {
  const [result] = await pool.query(
    "DELETE FROM change_requests WHERE student_id = ? AND kind = ? AND status = 'PENDING'",
    [studentId, kind]
  );
  if (result.affectedRows === 0) throw new ApiError(404, 'REQUEST_NOT_FOUND', 'You have no open request of that kind.');
}

/**
 * Approves a pending request. The change to the student AND the status change
 * happen in ONE transaction, with the request row locked first — so two
 * lecturers approving at once cannot both apply it, and a failure (the group
 * filled up since the student asked, the new number was taken) leaves the
 * request PENDING and the student untouched.
 */
async function approve(kind, requestId, lecturerAccountId) {
  await withTransaction(async (conn) => {
    const [[request]] = await conn.query(
      `SELECT r.*, tg.group_code AS to_group_code
         FROM change_requests r LEFT JOIN lab_groups tg ON tg.group_id = r.to_group_id
        WHERE r.request_id = ? AND r.kind = ? AND r.status = 'PENDING' FOR UPDATE`,
      [requestId, kind]
    );
    if (!request) throw notFound();

    if (kind === 'GROUP') {
      // The same capacity-checked move every other path uses (group.service.js).
      await groupService.moveStudent(conn, { studentId: request.student_id, groupCode: request.to_group_code });
    } else {
      const [[clash]] = await conn.query('SELECT student_id FROM students WHERE student_number = ? FOR UPDATE', [
        request.new_number,
      ]);
      if (clash) throw new ApiError(409, 'DUPLICATE_STUDENT_NUMBER', 'That student number is already in use.');
      const [moved] = await conn.query(
        'UPDATE students SET student_number = ?, version = version + 1 WHERE student_id = ? AND active = 1',
        [request.new_number, request.student_id]
      );
      if (moved.affectedRows === 0) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student does not exist or is deleted.');
    }

    await conn.query(
      "UPDATE change_requests SET status = 'APPROVED', resolved_at = NOW(3), resolved_by = ? WHERE request_id = ?",
      [lecturerAccountId, request.request_id]
    );
  });
}

async function decline(kind, requestId, lecturerAccountId) {
  const [result] = await pool.query(
    `UPDATE change_requests SET status = 'DECLINED', resolved_at = NOW(3), resolved_by = ?
      WHERE request_id = ? AND kind = ? AND status = 'PENDING'`,
    [lecturerAccountId, requestId, kind]
  );
  if (result.affectedRows === 0) throw notFound();
}

module.exports = { createRequest, listRequests, latestForStudent, cancelOwn, approve, decline, KINDS };
