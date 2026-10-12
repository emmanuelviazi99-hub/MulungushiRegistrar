// services/group.service.js
//
// Everything about moving a student into, out of, or between lab groups
// goes through lockAndCheckSeat() below (via assignStudentToGroup() or
// reserveSeat()). That is deliberate: the brief
// requires the 15-seat limit to hold on EVERY path (direct lecturer
// assignment, an approved group-change request, registration) — so there
// is exactly one place that checks capacity, and every code path that can
// create a membership calls it. (An earlier version of POST /api/students
// counted and inserted on its own, without a lock — under concurrent requests
// a group at 14/15 reached 24/15. tests/capacity.test.js now guards that.)
//
// --- Why "SELECT COUNT(*) then INSERT" is unsafe -------------------------
// Two requests can both run the SELECT COUNT(*) before either has written
// anything. If the group has 14 members, both requests read 14, both see
// "14 < 15" and both proceed to write, leaving 16 members — the exact bug
// Challenge 1 asks you to prevent. Checking the count in the Android app
// has the same flaw for the same reason: the check and the write are not
// atomic, so two different devices can both pass the check using data
// that is already stale by the time either of them writes.
//
// --- How this fixes it -----------------------------------------------------
// We open a transaction and take `FOR UPDATE` locks on the group row and
// the student row before counting. A `SELECT ... FOR UPDATE` acquires a
// row lock inside the transaction. A second, concurrent transaction that
// wants to lock the SAME group row must wait until the first transaction
// commits or rolls back — so the two requests are serialised for exactly
// the moment that matters (the count-then-write), while everything else in
// the system carries on running concurrently. Whichever transaction gets
// the lock first sees an up-to-date count and, if it writes, the count the
// second transaction then sees (after it gets the lock) already reflects
// that write.

const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const { withTransaction } = require('../utils/transaction');

const UNASSIGNED = null;

/**
 * Look up a group_id by its code (e.g. "G01"). Returns null for the
 * special "UNASSIGNED" value, which is a real, valid target (it just means
 * group_id = NULL on the student row).
 */
async function resolveGroupId(conn, groupCode) {
  if (!groupCode || groupCode.toUpperCase() === 'UNASSIGNED') return UNASSIGNED;

  const [rows] = await conn.query('SELECT group_id, capacity FROM lab_groups WHERE group_code = ? AND active = 1', [
    groupCode.toUpperCase(),
  ]);
  if (rows.length === 0) throw new ApiError(400, 'INVALID_GROUP', `Unknown lab group "${groupCode}".`);
  return rows[0];
}

/**
 * THE seat check. Locks the group row, then counts its active members WITH a
 * lock, and throws GROUP_FULL if there is no room. Must be called inside an
 * open transaction (`conn`), and the caller must write the membership in that
 * SAME transaction before committing — the lock is what makes the count and
 * the write atomic with respect to every other request.
 *
 * Every path that can add someone to a group calls this: a lecturer assigning
 * (assignStudentToGroup), a lecturer creating a student straight into a group
 * (reserveSeat, used by student.service), an approved group-change request,
 * and the offline-sync CREATE/TRANSFER operations.
 */
async function lockAndCheckSeat(conn, target, alreadyInThisGroup) {
  await conn.query('SELECT group_id FROM lab_groups WHERE group_id = ? FOR UPDATE', [target.group_id]);

  const [[{ activeCount }]] = await conn.query(
    'SELECT COUNT(*) AS activeCount FROM students WHERE group_id = ? AND active = 1 FOR UPDATE',
    [target.group_id]
  );

  if (!alreadyInThisGroup && activeCount >= target.capacity) {
    throw new ApiError(409, 'GROUP_FULL', 'This lab group already has 15 active students.');
  }
}

/**
 * For creating a NEW student directly into a group. Takes the seat lock and
 * returns the group_id to write (or null for Unassigned). The caller INSERTs
 * the student in the same transaction, then commits.
 */
async function reserveSeat(conn, groupCode) {
  const target = await resolveGroupId(conn, groupCode);
  if (target === UNASSIGNED) return null;
  await lockAndCheckSeat(conn, target, false);
  return target.group_id;
}

/**
 * Moves a student into `groupCode` (or to Unassigned) using a transaction the
 * caller already opened — so an approval can move the student AND mark the
 * request approved atomically (services/request.service.js).
 *
 * @param conn             an open transaction
 * @param studentId        the student's immutable UUID
 * @param groupCode        target group code, or "UNASSIGNED" / null
 * @param expectedVersion  optional — if given, the student row must still
 *                         be at this version (optimistic concurrency)
 * @returns the updated student row (plain object)
 */
async function moveStudent(conn, { studentId, groupCode, expectedVersion }) {
  // Lock the student row first (consistent lock ORDER across the whole
  // app — student, then group — avoids a deadlock between two transfers
  // that would otherwise lock the same two rows in opposite order).
  const [studentRows] = await conn.query('SELECT * FROM students WHERE student_id = ? FOR UPDATE', [studentId]);
  const student = studentRows[0];
  if (!student || !student.active) {
    throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student does not exist or is deleted.');
  }
  if (expectedVersion !== undefined && expectedVersion !== null && student.version !== expectedVersion) {
    throw new ApiError(409, 'VERSION_CONFLICT', 'This record changed since you last read it. Reload and retry.');
  }

  const target = await resolveGroupId(conn, groupCode); // null, or { group_id, capacity }

  if (target !== UNASSIGNED) {
    // A student already in this group doesn't take a NEW seat if they're
    // somehow re-assigned to the group they're already in.
    await lockAndCheckSeat(conn, target, student.group_id === target.group_id);
  }

  await conn.query('UPDATE students SET group_id = ?, version = version + 1 WHERE student_id = ?', [
    target === UNASSIGNED ? null : target.group_id,
    studentId,
  ]);
  const [updatedRows] = await conn.query('SELECT * FROM students WHERE student_id = ?', [studentId]);
  return updatedRows[0];
}

/**
 * moveStudent in its own transaction. On any failure (GROUP_FULL, a version
 * conflict) everything rolls back, so the student's previous group is untouched.
 */
async function assignStudentToGroup(options) {
  return withTransaction((conn) => moveStudent(conn, options));
}

/** Active-member counts per group, for the lecturer dashboard. */
async function getGroupTotals() {
  const [rows] = await pool.query(
    `SELECT g.group_code, g.capacity, COUNT(s.student_id) AS activeCount
     FROM lab_groups g
     LEFT JOIN students s ON s.group_id = g.group_id AND s.active = 1
     WHERE g.active = 1
     GROUP BY g.group_id
     ORDER BY g.group_code`
  );
  return rows;
}

module.exports = { assignStudentToGroup, moveStudent, reserveSeat, getGroupTotals, resolveGroupId };
