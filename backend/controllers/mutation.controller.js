const crypto = require('crypto');
const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const { withIdempotency } = require('../services/idempotency.service');
const groupService = require('../services/group.service');
const { serializeStudent } = require('../utils/serialize');

async function joinedStudent(studentId) {
  const [rows] = await pool.query(`
    SELECT s.*, p.code AS programme_code, g.group_code AS group_code
    FROM students s JOIN programmes p ON p.programme_id = s.programme_id
    LEFT JOIN lab_groups g ON g.group_id = s.group_id
    WHERE s.student_id = ?`, [studentId]);
  return rows[0];
}

function lecturerOnly(req, role) {
  if (req.user.role !== role) throw new ApiError(403, 'FORBIDDEN', 'This operation is not allowed for your role.');
}

async function mutate(req, res) {
  const { operationId, type, studentId, baseVersion, payload = {} } = req.body || {};
  if (!operationId || !type) throw new ApiError(422, 'VALIDATION_ERROR', 'operationId and type are required.');

  const effect = async () => {
    let row;

    if (type === 'CREATE') {
      lecturerOnly(req, 'LECTURER');
      const { fullName, studentNumber, programmeCode, groupCode } = payload;
      if (!fullName || !/^\d{9}$/.test(String(studentNumber || '')) || !programmeCode) {
        throw new ApiError(422, 'VALIDATION_ERROR', 'fullName, a 9-digit studentNumber and programmeCode are required.');
      }
      const [existing] = await pool.query('SELECT student_id FROM students WHERE student_number = ?', [studentNumber]);
      if (existing.length) throw new ApiError(409, 'DUPLICATE_STUDENT_NUMBER', 'A student with that number already exists.');
      const [prog] = await pool.query('SELECT programme_id FROM programmes WHERE code = ?', [String(programmeCode).toUpperCase()]);
      if (!prog.length) throw new ApiError(400, 'INVALID_PROGRAMME', 'Unknown programme code.');
      let groupId = null;
      if (groupCode && String(groupCode).toUpperCase() !== 'UNASSIGNED') {
        const [groups] = await pool.query('SELECT group_id, capacity FROM lab_groups WHERE group_code = ? AND active = 1', [String(groupCode).toUpperCase()]);
        if (!groups.length) throw new ApiError(400, 'INVALID_GROUP', 'Unknown lab group.');
        const [[count]] = await pool.query('SELECT COUNT(*) AS activeCount FROM students WHERE group_id = ? AND active = 1', [groups[0].group_id]);
        if (count.activeCount >= groups[0].capacity) throw new ApiError(409, 'GROUP_FULL', 'This lab group is full.');
        groupId = groups[0].group_id;
      }
      const id = crypto.randomUUID();
      await pool.query('INSERT INTO students (student_id, student_number, full_name, programme_id, group_id) VALUES (?, ?, ?, ?, ?)',
        [id, studentNumber, fullName, prog[0].programme_id, groupId]);
      row = await joinedStudent(id);
    } else if (type === 'EDIT') {
      const targetId = req.user.role === 'STUDENT' ? req.user.studentId : studentId;
      if (!targetId) throw new ApiError(422, 'VALIDATION_ERROR', 'studentId is required for this edit.');
      const fields = [], values = [];
      if (payload.fullName !== undefined) { fields.push('full_name = ?'); values.push(String(payload.fullName).trim()); }
      if (payload.programmeCode !== undefined) {
        const [prog] = await pool.query('SELECT programme_id FROM programmes WHERE code = ?', [String(payload.programmeCode).toUpperCase()]);
        if (!prog.length) throw new ApiError(400, 'INVALID_PROGRAMME', 'Unknown programme code.');
        fields.push('programme_id = ?'); values.push(prog[0].programme_id);
      }
      if (!fields.length) throw new ApiError(422, 'VALIDATION_ERROR', 'Nothing to update.');
      fields.push('version = version + 1');
      let sql = `UPDATE students SET ${fields.join(', ')} WHERE student_id = ? AND active = 1`;
      values.push(targetId);
      if (baseVersion !== undefined && baseVersion !== null) { sql += ' AND version = ?'; values.push(baseVersion); }
      const [result] = await pool.query(sql, values);
      if (!result.affectedRows) {
        row = await joinedStudent(targetId);
        if (!row) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student not found.');
        throw new ApiError(409, 'VERSION_CONFLICT', 'This record changed since you last read it.');
      }
      row = await joinedStudent(targetId);
    } else if (type === 'DELETE') {
      lecturerOnly(req, 'LECTURER');
      if (!studentId) throw new ApiError(422, 'VALIDATION_ERROR', 'studentId is required.');
      const [result] = await pool.query('UPDATE students SET active = 0, deleted_at = NOW(3), deletion_marker = ?, version = version + 1, group_id = NULL WHERE student_id = ? AND active = 1',
        [`DEL-${crypto.randomUUID()}`, studentId]);
      if (!result.affectedRows) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Student not found.');
      await pool.query('UPDATE accounts SET active = 0 WHERE student_id = ?', [studentId]);
      return { statusCode: 200, body: { success: true, status: 'APPLIED', record: null } };
    } else if (type === 'TRANSFER') {
      lecturerOnly(req, 'LECTURER');
      if (!studentId || !payload.groupCode) throw new ApiError(422, 'VALIDATION_ERROR', 'studentId and groupCode are required.');
      const raw = await groupService.assignStudentToGroup({ studentId, groupCode: payload.groupCode, expectedVersion: baseVersion });
      row = await joinedStudent(raw.student_id);
    } else {
      throw new ApiError(422, 'VALIDATION_ERROR', `Unsupported operation type: ${type}`);
    }

    return { statusCode: 200, body: { success: true, status: 'APPLIED', record: serializeStudent(row) } };
  };

  const result = await withIdempotency(pool, {
    operationId,
    accountId: req.user.accountId,
    requestBody: req.body,
  }, effect);

  const body = typeof result.body === 'string' ? JSON.parse(result.body) : result.body;
  res.status(result.statusCode).json(body);
}

module.exports = { mutate };
