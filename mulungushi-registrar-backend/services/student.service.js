// services/student.service.js
//
// Everything that reads or writes a roster row (the `students` table) lives
// here, and the HTTP controllers only translate requests into calls:
//
//   findStudent / listStudents   the ONE query that joins programme + group codes
//   createStudent                lecturer adds a student (POST /students, offline CREATE)
//   updateStudent                name / programme, and (lecturer only) NRC / birth date
//                                (PUT /students/:id, PUT /students/me, offline EDIT)
//   deleteStudent                soft delete (DELETE /students/:id, offline DELETE)
//
// Before this file existed the same SQL was written out three times (the REST
// controller, the offline-sync controller and the account service), and the
// copies had already drifted: the offline EDIT skipped the name check and the
// offline DELETE was not one transaction. One implementation cannot drift.
//
// A service that writes to the database validates its own input. The HTTP
// layer's express-validator rules give friendlier messages first, but a write
// must not depend on every caller remembering to validate.

const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const { withTransaction } = require('../utils/transaction');
const groupService = require('./group.service');
const v = require('../utils/validators');

// Every read of a student joins the reference tables, so the API answers with
// readable codes ("CS", "G01") and never makes the app remember integer ids.
const STUDENT_VIEW_SQL = `
  SELECT s.*, p.code AS programme_code, g.group_code AS group_code
    FROM students s
    JOIN programmes p      ON p.programme_id = s.programme_id
    LEFT JOIN lab_groups g ON g.group_id     = s.group_id`;

const NOT_FOUND_MESSAGE = 'Student does not exist or is deleted.';

function fail(field, message) {
  return new ApiError(422, 'VALIDATION_ERROR', `${field}: ${message}`);
}

/** Turns MySQL's duplicate-key error into the specific 409 the client needs. */
function translateDuplicate(err) {
  if (err && err.code === 'ER_DUP_ENTRY') {
    const msg = String(err.sqlMessage || '');
    if (msg.includes('uq_student_nrc')) {
      return new ApiError(409, 'DUPLICATE_NRC', 'A student with that NRC already exists.');
    }
    if (msg.includes('uq_student_number')) {
      return new ApiError(409, 'DUPLICATE_STUDENT_NUMBER', 'A student with that number already exists.');
    }
  }
  return err;
}

// --- reads ------------------------------------------------------------------

/** One student with programme/group codes, or null. Deleted students are hidden unless asked for. */
async function findStudent(studentId, { db = pool, includeDeleted = false } = {}) {
  const [rows] = await db.query(
    `${STUDENT_VIEW_SQL} WHERE s.student_id = ?${includeDeleted ? '' : ' AND s.active = 1'}`,
    [studentId]
  );
  return rows[0] || null;
}

/** Like findStudent, but a missing student is a 404. */
async function getStudent(studentId, options) {
  const row = await findStudent(studentId, options);
  if (!row) throw new ApiError(404, 'STUDENT_NOT_FOUND', NOT_FOUND_MESSAGE);
  return row;
}

/**
 * The lecturer's roster search. A 9-digit search term is an exact student-number
 * lookup; anything else searches names. programme / group filters combine with it.
 */
async function listStudents({ search, programme, group, page = 1, limit = 20 }) {
  const where = ['s.active = 1'];
  const params = [];

  const term = search ? String(search).trim() : '';
  if (term) {
    if (/^\d{9}$/.test(term)) {
      where.push('s.student_number = ?');
      params.push(term);
    } else {
      where.push('s.full_name LIKE ?');
      params.push(`%${term}%`);
    }
  }
  if (programme) {
    where.push('p.code = ?');
    params.push(String(programme).toUpperCase());
  }
  if (group) {
    if (String(group).toUpperCase() === 'UNASSIGNED') {
      where.push('s.group_id IS NULL');
    } else {
      where.push('g.group_code = ?');
      params.push(String(group).toUpperCase());
    }
  }
  const whereSql = `WHERE ${where.join(' AND ')}`;

  // The count reuses the same joins and filters as the page query.
  const [[{ total }]] = await pool.query(
    `SELECT COUNT(*) AS total FROM (${STUDENT_VIEW_SQL} ${whereSql}) AS matches`,
    params
  );
  const [rows] = await pool.query(
    `${STUDENT_VIEW_SQL} ${whereSql} ORDER BY s.full_name LIMIT ? OFFSET ?`,
    [...params, limit, (page - 1) * limit]
  );
  return { rows, total };
}

/** programmes.code -> programme_id. The programmes table is the only list of valid codes. */
async function programmeIdFor(db, programmeCode) {
  const [rows] = await db.query('SELECT programme_id FROM programmes WHERE code = ?', [
    String(programmeCode ?? '').trim().toUpperCase(),
  ]);
  if (rows.length === 0) throw new ApiError(400, 'INVALID_PROGRAMME', 'Unknown programme code.');
  return rows[0].programme_id;
}

// --- writes -----------------------------------------------------------------

/**
 * Lecturer adds a student. When a group is requested, the seat lock is taken
 * BEFORE the insert inside one transaction, so "check the count" and "insert"
 * can never be separated by another request (see group.service.js).
 *
 * @param input { fullName, studentNumber, nrc, dateOfBirth, programmeCode, groupCode? }
 * @returns the new student_id
 */
async function createStudent(input) {
  const fullName = String(input.fullName ?? '').trim();
  const studentNumber = String(input.studentNumber ?? '').trim();
  const nrc = v.normaliseNrc(input.nrc);
  const { dateOfBirth } = input;
  const groupCode = input.groupCode ? String(input.groupCode).trim().toUpperCase() : null;

  if (!v.isValidName(fullName)) {
    throw fail('fullName', 'must be 2-100 characters: letters, spaces, apostrophes, hyphens or full stops');
  }
  if (!v.isValidStudentNumber(studentNumber)) throw fail('studentNumber', 'must be exactly 9 digits, no spaces');
  if (!v.isValidNrc(nrc)) throw fail('nrc', 'must look like 123456/10/1');
  if (!v.isValidDob(dateOfBirth)) throw fail('dateOfBirth', 'must be a real date in the past, formatted YYYY-MM-DD');

  try {
    return await withTransaction(async (conn) => {
      const programmeId = await programmeIdFor(conn, input.programmeCode);
      // Takes the group lock and throws GROUP_FULL if there is no seat. null = Unassigned.
      const groupId = groupCode ? await groupService.reserveSeat(conn, groupCode) : null;

      const studentId = crypto.randomUUID();
      await conn.query(
        `INSERT INTO students (student_id, student_number, nrc, date_of_birth, full_name, programme_id, group_id)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [studentId, studentNumber, nrc, dateOfBirth, fullName, programmeId, groupId]
      );
      return studentId;
    });
  } catch (err) {
    throw translateDuplicate(err); // the rollback already left nothing half-created
  }
}

/**
 * Changes a student's own details.
 *
 * @param changes  { fullName?, programmeCode?, nrc?, dateOfBirth? } — anything else is ignored
 * @param options.allowIdentityFields  true for lecturers. NRC and birth date are
 *        how a student proves who they are at sign-up, so a student can never
 *        change their own (those fields are silently ignored for them).
 *        The student NUMBER is never changed here: that goes through a
 *        lecturer-approved change request (request.service.js).
 * @param options.expectedVersion  optimistic concurrency — if given, the row must
 *        still be at this version or the update is refused with VERSION_CONFLICT.
 * @returns the updated student (with codes)
 */
async function updateStudent(studentId, changes = {}, { allowIdentityFields = false, expectedVersion } = {}) {
  const sets = [];
  const values = [];

  if (changes.fullName !== undefined) {
    const fullName = String(changes.fullName).trim();
    if (!v.isValidName(fullName)) {
      throw fail('fullName', 'must be 2-100 characters: letters, spaces, apostrophes, hyphens or full stops');
    }
    sets.push('full_name = ?');
    values.push(fullName);
  }
  if (changes.programmeCode !== undefined) {
    sets.push('programme_id = ?');
    values.push(await programmeIdFor(pool, changes.programmeCode));
  }
  if (allowIdentityFields && changes.nrc !== undefined) {
    const nrc = v.normaliseNrc(changes.nrc);
    if (!v.isValidNrc(nrc)) throw fail('nrc', 'must look like 123456/10/1');
    sets.push('nrc = ?');
    values.push(nrc);
  }
  if (allowIdentityFields && changes.dateOfBirth !== undefined) {
    if (!v.isValidDob(changes.dateOfBirth)) {
      throw fail('dateOfBirth', 'must be a real date in the past, formatted YYYY-MM-DD');
    }
    sets.push('date_of_birth = ?');
    values.push(changes.dateOfBirth);
  }
  if (sets.length === 0) throw new ApiError(422, 'VALIDATION_ERROR', 'Nothing to update.');

  sets.push('version = version + 1');
  let sql = `UPDATE students SET ${sets.join(', ')} WHERE student_id = ? AND active = 1`;
  values.push(studentId);
  if (expectedVersion !== undefined && expectedVersion !== null) {
    sql += ' AND version = ?';
    values.push(expectedVersion);
  }

  let result;
  try {
    [result] = await pool.query(sql, values);
  } catch (err) {
    throw translateDuplicate(err); // e.g. DUPLICATE_NRC
  }
  if (result.affectedRows === 0) {
    // Tell the two failure modes apart so the app can react correctly.
    await getStudent(studentId); // throws STUDENT_NOT_FOUND if that is the reason
    throw new ApiError(409, 'VERSION_CONFLICT', 'This record changed since you last read it. Reload and retry.');
  }
  return getStudent(studentId);
}

/**
 * Soft delete. Setting deleted_at is all it takes: `active` is computed from it
 * by the database. The seat is released (group_id = NULL), the student number
 * and NRC stay reserved, and the student's login is disabled — all in one
 * transaction so a failure part-way cannot leave a deleted student holding a seat.
 */
async function deleteStudent(studentId) {
  await withTransaction(async (conn) => {
    const [result] = await conn.query(
      `UPDATE students SET deleted_at = NOW(3), group_id = NULL, version = version + 1
        WHERE student_id = ? AND deleted_at IS NULL`,
      [studentId]
    );
    if (result.affectedRows === 0) {
      throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student does not exist or was already deleted.');
    }
    await conn.query('UPDATE accounts SET active = 0 WHERE student_id = ?', [studentId]);
  });
}

module.exports = {
  findStudent,
  getStudent,
  listStudents,
  programmeIdFor,
  createStudent,
  updateStudent,
  deleteStudent,
  translateDuplicate,
};
