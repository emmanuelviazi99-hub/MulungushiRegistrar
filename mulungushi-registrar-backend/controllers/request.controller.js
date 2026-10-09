// controllers/request.controller.js
//
// Students can only ever PROPOSE a group change or a number correction.
// Only a lecturer can approve or decline. The record itself never changes
// until an approval happens — matching the brief: "An offline request is
// Pending until confirmed by the server."

const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const groupService = require('../services/group.service');
const { serializeGroupRequest, serializeNumberCorrectionRequest } = require('../utils/serialize');

// ============================= Group requests ==============================

// POST /api/group-requests   (student)   body: { groupCode, reason? }
async function createGroupRequest(req, res) {
  const { groupCode, reason } = req.body;
  const studentId = req.user.studentId;

  const [[student]] = await pool.query('SELECT group_id FROM students WHERE student_id = ? AND active = 1', [
    studentId,
  ]);
  if (!student) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Your profile could not be found.');

  const toGroup = await groupService.resolveGroupId(pool, groupCode); // null or { group_id }
  const toGroupId = toGroup === null ? null : toGroup.group_id;

  // One open request at a time per student keeps the lecturer's queue sane.
  const [[openExisting]] = await pool.query(
    "SELECT request_id FROM group_requests WHERE student_id = ? AND status = 'PENDING'",
    [studentId]
  );
  if (openExisting) {
    throw new ApiError(409, 'REQUEST_ALREADY_PENDING', 'You already have an open group-change request.');
  }

  const [result] = await pool.query(
    'INSERT INTO group_requests (student_id, from_group_id, to_group_id, reason) VALUES (?, ?, ?, ?)',
    [studentId, student.group_id, toGroupId, reason || null]
  );

  res.status(201).json({ success: true, requestId: result.insertId, status: 'PENDING' });
}

// GET /api/group-requests   (lecturer)   ?status=PENDING
async function listGroupRequests(req, res) {
  const status = (req.query.status || 'PENDING').toUpperCase();
  const [rows] = await pool.query(
    `SELECT r.*, s.full_name, s.student_number, fg.group_code AS from_group_code, tg.group_code AS to_group_code
     FROM group_requests r
     JOIN students s ON s.student_id = r.student_id
     LEFT JOIN lab_groups fg ON fg.group_id = r.from_group_id
     LEFT JOIN lab_groups tg ON tg.group_id = r.to_group_id
     WHERE r.status = ?
     ORDER BY r.requested_at ASC`,
    [status]
  );
  res.json({ success: true, requests: rows.map(serializeGroupRequest) });
}

// PUT /api/group-requests/:id/approve   (lecturer)
async function approveGroupRequest(req, res) {
  const [[request]] = await pool.query("SELECT * FROM group_requests WHERE request_id = ? AND status = 'PENDING'", [
    req.params.id,
  ]);
  if (!request) throw new ApiError(404, 'REQUEST_NOT_FOUND', 'No pending request with that id.');

  const [[targetGroup]] = request.to_group_id
    ? await pool.query('SELECT group_code FROM lab_groups WHERE group_id = ?', [request.to_group_id])
    : [[{ group_code: null }]];

  // Re-checks capacity at approval time — the group may have filled up
  // since the student asked. On GROUP_FULL this throws, the request stays
  // PENDING, and the student's current group is untouched (brief, section 16).
  await groupService.assignStudentToGroup({
    studentId: request.student_id,
    groupCode: targetGroup.group_code, // null -> Unassigned, handled by the service
  });

  await pool.query(
    "UPDATE group_requests SET status = 'APPROVED', resolved_at = NOW(3), resolved_by = ? WHERE request_id = ?",
    [req.user.accountId, request.request_id]
  );

  res.json({ success: true, status: 'APPROVED' });
}

// PUT /api/group-requests/:id/decline   (lecturer)
async function declineGroupRequest(req, res) {
  const [result] = await pool.query(
    "UPDATE group_requests SET status = 'DECLINED', resolved_at = NOW(3), resolved_by = ? WHERE request_id = ? AND status = 'PENDING'",
    [req.user.accountId, req.params.id]
  );
  if (result.affectedRows === 0) throw new ApiError(404, 'REQUEST_NOT_FOUND', 'No pending request with that id.');
  res.json({ success: true, status: 'DECLINED' });
}

// ======================= Number correction requests =========================

// POST /api/number-correction-requests   (student)   body: { newNumber, reason? }
async function createNumberCorrectionRequest(req, res) {
  const { newNumber, reason } = req.body;
  const studentId = req.user.studentId;

  const [[student]] = await pool.query('SELECT student_number FROM students WHERE student_id = ? AND active = 1', [
    studentId,
  ]);
  if (!student) throw new ApiError(404, 'STUDENT_NOT_FOUND', 'Your profile could not be found.');

  const [[openExisting]] = await pool.query(
    "SELECT request_id FROM number_correction_requests WHERE student_id = ? AND status = 'PENDING'",
    [studentId]
  );
  if (openExisting) {
    throw new ApiError(409, 'REQUEST_ALREADY_PENDING', 'You already have an open number-correction request.');
  }

  const [result] = await pool.query(
    'INSERT INTO number_correction_requests (student_id, old_number, new_number, reason) VALUES (?, ?, ?, ?)',
    [studentId, student.student_number, newNumber, reason || null]
  );

  res.status(201).json({ success: true, requestId: result.insertId, status: 'PENDING' });
}

// GET /api/number-correction-requests   (lecturer)   ?status=PENDING
async function listNumberCorrectionRequests(req, res) {
  const status = (req.query.status || 'PENDING').toUpperCase();
  const [rows] = await pool.query(
    `SELECT r.*, s.full_name
     FROM number_correction_requests r
     JOIN students s ON s.student_id = r.student_id
     WHERE r.status = ?
     ORDER BY r.requested_at ASC`,
    [status]
  );
  res.json({ success: true, requests: rows.map(serializeNumberCorrectionRequest) });
}

// PUT /api/number-correction-requests/:id/approve   (lecturer)
async function approveNumberCorrectionRequest(req, res) {
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();

    const [[request]] = await conn.query(
      "SELECT * FROM number_correction_requests WHERE request_id = ? AND status = 'PENDING' FOR UPDATE",
      [req.params.id]
    );
    if (!request) throw new ApiError(404, 'REQUEST_NOT_FOUND', 'No pending request with that id.');

    const [[clash]] = await conn.query('SELECT student_id FROM students WHERE student_number = ? FOR UPDATE', [
      request.new_number,
    ]);
    if (clash) {
      throw new ApiError(409, 'DUPLICATE_STUDENT_NUMBER', 'That student number is already in use.');
    }

    // student_id is untouched — only the number changes, identity stays the same.
    await conn.query('UPDATE students SET student_number = ?, version = version + 1 WHERE student_id = ?', [
      request.new_number,
      request.student_id,
    ]);
    await conn.query(
      "UPDATE number_correction_requests SET status = 'APPROVED', resolved_at = NOW(3), resolved_by = ? WHERE request_id = ?",
      [req.user.accountId, request.request_id]
    );

    await conn.commit();
    res.json({ success: true, status: 'APPROVED' });
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
}

// PUT /api/number-correction-requests/:id/decline   (lecturer)
async function declineNumberCorrectionRequest(req, res) {
  const [result] = await pool.query(
    "UPDATE number_correction_requests SET status = 'DECLINED', resolved_at = NOW(3), resolved_by = ? WHERE request_id = ? AND status = 'PENDING'",
    [req.user.accountId, req.params.id]
  );
  if (result.affectedRows === 0) throw new ApiError(404, 'REQUEST_NOT_FOUND', 'No pending request with that id.');
  res.json({ success: true, status: 'DECLINED' });
}

module.exports = {
  createGroupRequest,
  listGroupRequests,
  approveGroupRequest,
  declineGroupRequest,
  createNumberCorrectionRequest,
  listNumberCorrectionRequests,
  approveNumberCorrectionRequest,
  declineNumberCorrectionRequest,
};
