// controllers/student.controller.js
//
// HTTP wrappers around services/student.service.js. No SQL lives here.

const studentService = require('../services/student.service');
const { serializeStudent } = require('../utils/serialize');

// Single-record views carry the full NRC and birth date; lists only a masked NRC.
const reply = (res, row, status = 200) =>
  res.status(status).json({ success: true, student: serializeStudent(row, { full: true }) });

// --- Student's own profile -------------------------------------------------

// GET /api/students/me
async function getMe(req, res) {
  reply(res, await studentService.getStudent(req.user.studentId));
}

// PUT /api/students/me
// A student may change only their name and programme. The id always comes
// from the token, never the URL, so nobody can edit someone else this way.
async function updateMe(req, res) {
  const { fullName, programmeCode } = req.body;
  reply(res, await studentService.updateStudent(req.user.studentId, { fullName, programmeCode }));
}

// --- Lecturer ---------------------------------------------------------------

// GET /api/students?search=&programme=&group=&page=&limit=
async function list(req, res) {
  const page = parseInt(req.query.page, 10) || 1;
  const limit = Math.min(parseInt(req.query.limit, 10) || 20, 100);
  const { rows, total } = await studentService.listStudents({ ...req.query, page, limit });
  res.json({
    success: true,
    students: rows.map((r) => serializeStudent(r)), // masked NRC only
    page,
    limit,
    total,
    totalPages: Math.ceil(total / limit),
  });
}

// GET /api/students/:id
async function getOne(req, res) {
  reply(res, await studentService.getStudent(req.params.id));
}

// POST /api/students
// Adds a student to the roster (name, number, NRC, birth date, programme,
// optional group). This does NOT create a login: the student signs up
// themselves through /api/auth/register/*, typing the number, NRC and birth
// date recorded here to prove who they are.
async function create(req, res) {
  const studentId = await studentService.createStudent(req.body);
  reply(res, await studentService.getStudent(studentId), 201);
}

// PUT /api/students/:id — a lecturer may also correct a mistyped NRC or birth date.
async function update(req, res) {
  const { fullName, programmeCode, nrc, dateOfBirth, version } = req.body;
  reply(
    res,
    await studentService.updateStudent(
      req.params.id,
      { fullName, programmeCode, nrc, dateOfBirth },
      { allowIdentityFields: true, expectedVersion: version }
    )
  );
}

// DELETE /api/students/:id — soft delete (see studentService.deleteStudent).
async function remove(req, res) {
  await studentService.deleteStudent(req.params.id);
  res.json({ success: true, message: 'Student soft-deleted.' });
}

module.exports = { getMe, updateMe, list, getOne, create, update, remove };
