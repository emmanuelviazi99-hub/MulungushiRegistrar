// controllers/group.controller.js

const pool = require('../config/database');
const groupService = require('../services/group.service');
const studentService = require('../services/student.service');
const { withIdempotency } = require('../services/idempotency.service');
const { serializeStudent } = require('../utils/serialize');

// POST /api/students/:id/group
// Body: { groupCode: "G02", operationId?: "uuid", version? }
// operationId is optional but strongly recommended: pass it so a retried
// request (lost response, app restarted mid-call) cannot double-count.
async function assignGroup(req, res) {
  const { groupCode, operationId, version } = req.body;
  const studentId = req.params.id;

  const effect = async () => {
    const student = await groupService.assignStudentToGroup({
      studentId,
      groupCode,
      expectedVersion: version,
    });
    const row = await studentService.getStudent(student.student_id); // with programme/group codes
    return { statusCode: 200, body: { success: true, student: serializeStudent(row) } };
  };

  const result = await withIdempotency(
    pool,
    { operationId, accountId: req.user.accountId, requestBody: req.body },
    effect
  );

  res.status(result.statusCode).json(result.body);
}

// GET /api/groups — totals, for the lecturer dashboard
async function listTotals(req, res) {
  const totals = await groupService.getGroupTotals();
  res.json({
    success: true,
    groups: totals.map((g) => ({
      groupCode: g.group_code,
      capacity: g.capacity,
      activeCount: g.activeCount,
      full: g.activeCount >= g.capacity,
    })),
  });
}

module.exports = { assignGroup, listTotals };
