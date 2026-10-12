// controllers/mutation.controller.js
//
// POST /api/students/mutate — the offline-sync endpoint. The Android app
// queues changes while offline and replays them here, each with its own
// operationId so a replay after a lost response is applied only once
// (services/idempotency.service.js).
//
// Every operation calls the SAME service function as the matching REST
// endpoint, so offline and online changes obey identical rules:
//   CREATE    studentService.createStudent        (POST   /api/students)
//   EDIT      studentService.updateStudent        (PUT    /api/students/:id or /me)
//   DELETE    studentService.deleteStudent        (DELETE /api/students/:id)
//   TRANSFER  groupService.assignStudentToGroup   (POST   /api/students/:id/group)

const pool = require('../config/database');
const ApiError = require('../utils/ApiError');
const { withIdempotency } = require('../services/idempotency.service');
const groupService = require('../services/group.service');
const studentService = require('../services/student.service');
const { serializeStudent } = require('../utils/serialize');

function requireLecturer(req) {
  if (req.user.role !== 'LECTURER') {
    throw new ApiError(403, 'FORBIDDEN', 'This operation is not allowed for your role.');
  }
}

function requireStudentId(studentId) {
  if (!studentId) throw new ApiError(422, 'VALIDATION_ERROR', 'studentId is required.');
  return studentId;
}

async function apply(req) {
  const { type, studentId, baseVersion, payload = {} } = req.body;
  const isLecturer = req.user.role === 'LECTURER';

  switch (type) {
    case 'CREATE': {
      requireLecturer(req);
      return studentService.createStudent(payload);
    }
    case 'EDIT': {
      // A student can only ever edit themselves: the id comes from the token.
      const targetId = isLecturer ? requireStudentId(studentId) : req.user.studentId;
      const row = await studentService.updateStudent(targetId, payload, {
        allowIdentityFields: isLecturer,
        expectedVersion: baseVersion,
      });
      return row.student_id;
    }
    case 'DELETE': {
      requireLecturer(req);
      await studentService.deleteStudent(requireStudentId(studentId));
      return null;
    }
    case 'TRANSFER': {
      requireLecturer(req);
      if (!payload.groupCode) throw new ApiError(422, 'VALIDATION_ERROR', 'payload.groupCode is required.');
      const row = await groupService.assignStudentToGroup({
        studentId: requireStudentId(studentId),
        groupCode: payload.groupCode,
        expectedVersion: baseVersion ?? undefined,
      });
      return row.student_id;
    }
    default:
      throw new ApiError(422, 'VALIDATION_ERROR', `Unsupported operation type: ${type}`);
  }
}

async function mutate(req, res) {
  const { operationId, type } = req.body || {};
  if (!operationId || !type) throw new ApiError(422, 'VALIDATION_ERROR', 'operationId and type are required.');

  const result = await withIdempotency(
    pool,
    { operationId, accountId: req.user.accountId, requestBody: req.body },
    async () => {
      const id = await apply(req);
      const record = id ? serializeStudent(await studentService.getStudent(id), { full: true }) : null;
      return { statusCode: 200, body: { success: true, status: 'APPLIED', record } };
    }
  );

  const body = typeof result.body === 'string' ? JSON.parse(result.body) : result.body;
  res.status(result.statusCode).json(body);
}

module.exports = { mutate };
