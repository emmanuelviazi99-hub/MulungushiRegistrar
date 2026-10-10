// controllers/student.controller.js

const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const claimService = require('../services/claim.service');
const { serializeStudent } = require('../utils/serialize');

// Every student SELECT joins programmes/lab_groups so the API can return
// human-readable codes (CS, G01) instead of making the Android app
// remember internal integer ids.
const STUDENT_SELECT = `
  SELECT s.*, p.code AS programme_code, g.group_code AS group_code
  FROM students s
  JOIN programmes p ON p.programme_id = s.programme_id
  LEFT JOIN lab_groups g ON g.group_id = s.group_id
`;

async function findActiveStudentRow(studentId) {
  const [rows] = await pool.query(`${STUDENT_SELECT} WHERE s.student_id = ? AND s.active = 1`, [studentId]);
  return rows[0];
}

// --- Student's own profile -------------------------------------------------

// GET /api/students/me
async function getMe(req, res) {
  const row = await findActiveStudentRow(req.user.studentId);
  if (!row) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Your profile could not be found.');
  res.json({ success: true, student: serializeStudent(row) });
}

// PUT /api/students/me
// Students may only edit name and programme — never their own number or
// group (those go through the request-and-approve flows) and never
// anyone else's record: the id always comes from the token, never a URL.
async function updateMe(req, res) {
  const { fullName, programmeCode } = req.body;
  const fields = [];
  const values = [];

  if (fullName !== undefined) {
    fields.push('full_name = ?');
    values.push(fullName.trim());
  }
  if (programmeCode !== undefined) {
    const [prog] = await pool.query('SELECT programme_id FROM programmes WHERE code = ?', [
      programmeCode.toUpperCase(),
    ]);
    if (prog.length === 0) throw new ApiError(400, 'INVALID_PROGRAMME', 'Unknown programme code.');
    fields.push('programme_id = ?');
    values.push(prog[0].programme_id);
  }
  if (fields.length === 0) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'Nothing to update.');
  }

  fields.push('version = version + 1');
  values.push(req.user.studentId);

  const [result] = await pool.query(`UPDATE students SET ${fields.join(', ')} WHERE student_id = ? AND active = 1`, values);
  if (result.affectedRows === 0) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Your profile could not be found.');

  const row = await findActiveStudentRow(req.user.studentId);
  res.json({ success: true, student: serializeStudent(row) });
}

// --- Lecturer: search / filter / list --------------------------------------

// GET /api/students?search=&programme=&group=&page=&limit=
async function list(req, res) {
  const { search, programme, group } = req.query;
  const page = parseInt(req.query.page, 10) || 1;
  const limit = Math.min(parseInt(req.query.limit, 10) || 20, 100);
  const offset = (page - 1) * limit;

  const where = ['s.active = 1'];
  const params = [];

  if (search) {
    // A 9-digit search term is treated as an exact student-number lookup;
    // anything else searches the name. Combinable with programme/group.
    if (/^\d{9}$/.test(search.trim())) {
      where.push('s.student_number = ?');
      params.push(search.trim());
    } else {
      where.push('s.full_name LIKE ?');
      params.push(`%${search.trim()}%`);
    }
  }
  if (programme) {
    where.push('p.code = ?');
    params.push(programme.toUpperCase());
  }
  if (group) {
    if (group.toUpperCase() === 'UNASSIGNED') {
      where.push('s.group_id IS NULL');
    } else {
      where.push('g.group_code = ?');
      params.push(group.toUpperCase());
    }
  }

  const whereSql = where.length ? `WHERE ${where.join(' AND ')}` : '';

  const [countRows] = await pool.query(
    `SELECT COUNT(*) AS total FROM students s
     JOIN programmes p ON p.programme_id = s.programme_id
     LEFT JOIN lab_groups g ON g.group_id = s.group_id
     ${whereSql}`,
    params
  );
  const total = countRows[0].total;

  const [rows] = await pool.query(
    `${STUDENT_SELECT} ${whereSql} ORDER BY s.full_name LIMIT ? OFFSET ?`,
    [...params, limit, offset]
  );

  res.json({
    success: true,
    students: rows.map(serializeStudent),
    page,
    limit,
    total,
    totalPages: Math.ceil(total / limit),
  });
}

// GET /api/students/:id
async function getOne(req, res) {
  const row = await findActiveStudentRow(req.params.id);
  if (!row) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student does not exist or is deleted.');
  res.json({ success: true, student: serializeStudent(row) });
}

// POST /api/students
// Lecturer creates a student record AND a one-time claim code. Nothing
// here creates a login account — that only happens via /auth/register.
async function create(req, res) {
  const { fullName, studentNumber, programmeCode, groupCode } = req.body;

  const [existing] = await pool.query('SELECT student_id FROM students WHERE student_number = ?', [studentNumber]);
  if (existing.length > 0) {
    throw new ApiError(409, 'DUPLICATE_STUDENT_NUMBER', 'A student with that number already exists.');
  }

  const [progRows] = await pool.query('SELECT programme_id FROM programmes WHERE code = ?', [programmeCode]);
  if (progRows.length === 0) throw new ApiError(400, 'INVALID_PROGRAMME', 'Unknown programme code.');

  let groupId = null;
  if (groupCode) {
    const [groupRows] = await pool.query('SELECT group_id, capacity FROM lab_groups WHERE group_code = ?', [
      groupCode,
    ]);
    if (groupRows.length === 0) throw new ApiError(400, 'INVALID_GROUP', 'Unknown lab group.');
    const [[{ activeCount }]] = await pool.query(
      'SELECT COUNT(*) AS activeCount FROM students WHERE group_id = ? AND active = 1',
      [groupRows[0].group_id]
    );
    if (activeCount >= groupRows[0].capacity) {
      throw new ApiError(409, 'GROUP_FULL', 'This lab group already has 15 active students.');
    }
    groupId = groupRows[0].group_id;
  }

  const studentId = crypto.randomUUID();
  await pool.query(
    'INSERT INTO students (student_id, student_number, full_name, programme_id, group_id) VALUES (?, ?, ?, ?, ?)',
    [studentId, studentNumber, fullName, progRows[0].programme_id, groupId]
  );

  const claimCode = await claimService.generateClaimCode(studentId);

  const row = await findActiveStudentRow(studentId);
  res.status(201).json({
    success: true,
    student: serializeStudent(row),
    claimCode, // shown ONCE — the lecturer hands this to the student out of band
  });
}

// PUT /api/students/:id
async function update(req, res) {
  const { fullName, programmeCode, version } = req.body;
  const fields = [];
  const values = [];

  if (fullName !== undefined) {
    fields.push('full_name = ?');
    values.push(fullName.trim());
  }
  if (programmeCode !== undefined) {
    const [prog] = await pool.query('SELECT programme_id FROM programmes WHERE code = ?', [
      programmeCode.toUpperCase(),
    ]);
    if (prog.length === 0) throw new ApiError(400, 'INVALID_PROGRAMME', 'Unknown programme code.');
    fields.push('programme_id = ?');
    values.push(prog[0].programme_id);
  }
  if (fields.length === 0) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'Nothing to update.');
  }

  fields.push('version = version + 1');

  let sql = `UPDATE students SET ${fields.join(', ')} WHERE student_id = ? AND active = 1`;
  values.push(req.params.id);
  if (version !== undefined) {
    sql += ' AND version = ?';
    values.push(version);
  }

  const [result] = await pool.query(sql, values);
  if (result.affectedRows === 0) {
    // Tell the two failure modes apart so the UI can react correctly.
    const row = await findActiveStudentRow(req.params.id);
    if (!row) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student does not exist or is deleted.');
    throw new ApiError(409, 'VERSION_CONFLICT', 'This record changed since you last read it. Reload and retry.');
  }

  const row = await findActiveStudentRow(req.params.id);
  res.json({ success: true, student: serializeStudent(row) });
}

// DELETE /api/students/:id — soft delete, releases the group seat once,
// keeps the student number reserved.
async function remove(req, res) {
  const marker = `DEL-${crypto.randomUUID()}`;
  const [result] = await pool.query(
    `UPDATE students
     SET active = 0, deleted_at = NOW(3), deletion_marker = ?, version = version + 1
     WHERE student_id = ? AND active = 1`,
    [marker, req.params.id]
  );
  if (result.affectedRows === 0) {
    throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student does not exist or was already deleted.');
  }

  // Disable (not delete) any linked login account, and release the seat
  // by clearing group_id so group counts no longer include this student.
  await pool.query('UPDATE accounts SET active = 0 WHERE student_id = ?', [req.params.id]);
  await pool.query('UPDATE students SET group_id = NULL WHERE student_id = ?', [req.params.id]);

  res.json({ success: true, message: 'Student soft-deleted.' });
}

module.exports = { getMe, updateMe, list, getOne, create, update, remove };
