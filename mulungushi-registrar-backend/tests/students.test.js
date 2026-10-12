// tests/students.test.js
//
// The roster: adding students with NRC + date of birth, what each role may see,
// duplicate protection, ownership, number corrections, soft delete, retries
// (idempotency) and the offline-sync CREATE operation.

process.env.NODE_ENV = 'test';

const crypto = require('crypto');
const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, loginAs, signUpStudent, nrcFor } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');

let lecturerToken;
const asLecturer = (req) => req.set('Authorization', `Bearer ${lecturerToken}`);

function newStudent(studentNumber, overrides = {}) {
  return { fullName: 'Ngosa Kabwe', studentNumber, nrc: nrcFor(studentNumber), dateOfBirth: '2003-05-17', programmeCode: 'CS', ...overrides };
}
const addStudent = (body) => asLecturer(request(app).post('/api/students')).send(body);
const countStudents = async () => (await pool.query('SELECT COUNT(*) AS n FROM students'))[0][0].n;

beforeAll(async () => {
  await resetDb();
  const lecturer = await createLecturer(pool);
  lecturerToken = await loginAs(app, lecturer.email, lecturer.password);
});
beforeEach(() => {
  outbox.length = 0;
});
afterAll(async () => {
  await pool.end();
});

// ---------------------------------------------------------------------------
describe('adding a student: NRC and date of birth', () => {
  test('a lecturer adds a student; the record carries NRC and date of birth, and no code is handed out', async () => {
    const res = await addStudent(newStudent('300000001'));
    expect(res.status).toBe(201);
    expect(res.body.student).toMatchObject({
      studentNumber: '300000001',
      nrc: '300000/00/1',
      nrcMasked: '30****/**/1',
      dateOfBirth: '2003-05-17', // exactly as entered: no time-zone shift
      programmeCode: 'CS',
      groupCode: null,
      version: 1,
    });
    expect(res.body.claimCode).toBeUndefined(); // the old hand-it-over claim code no longer exists
  });

  test('an NRC typed without slashes is stored in the canonical form', async () => {
    const res = await addStudent(newStudent('300000002', { nrc: '300000201' }));
    expect(res.status).toBe(201);
    expect(res.body.student.nrc).toBe('300000/20/1');
  });

  test('programme and group codes come from the database, not a list in the code', async () => {
    const before = await countStudents();
    const unknown = await addStudent(newStudent('300000010', { programmeCode: 'XX' }));
    expect(unknown.status).toBe(400);
    expect(unknown.body.error).toBe('INVALID_PROGRAMME');
    const noGroup = await addStudent(newStudent('300000011', { groupCode: 'G99' }));
    expect(noGroup.status).toBe(400);
    expect(noGroup.body.error).toBe('INVALID_GROUP');
    expect(await countStudents()).toBe(before);

    // Adding a programme and a lab group is a database insert — no code change.
    await pool.query("INSERT INTO programmes (code, name) VALUES ('SE', 'Software Engineering')");
    await pool.query("INSERT INTO lab_groups (group_code, capacity) VALUES ('G05', 15)");
    const added = await addStudent(newStudent('300000012', { programmeCode: 'se', groupCode: 'g05' }));
    expect(added.status).toBe(201);
    expect(added.body.student).toMatchObject({ programmeCode: 'SE', groupCode: 'G05' });
    const groups = await asLecturer(request(app).get('/api/groups'));
    expect(groups.body.groups.map((g) => g.groupCode)).toContain('G05');
  });

  test('lists show only a masked NRC and no birth date; opening one student shows the full record', async () => {
    const list = await asLecturer(request(app).get('/api/students?search=300000001'));
    expect(list.status).toBe(200);
    const row = list.body.students[0];
    expect(row.nrcMasked).toBe('30****/**/1');
    expect(row).not.toHaveProperty('nrc');
    expect(row).not.toHaveProperty('dateOfBirth');

    const one = await asLecturer(request(app).get(`/api/students/${row.studentId}`));
    expect(one.body.student).toMatchObject({ nrc: '300000/00/1', dateOfBirth: '2003-05-17' });
  });

  test('NRC and student number are each unique — and the same goes for deleted students', async () => {
    const sameNumber = await addStudent(newStudent('300000001', { nrc: '300000/99/9' }));
    expect(sameNumber.status).toBe(409);
    expect(sameNumber.body.error).toBe('DUPLICATE_STUDENT_NUMBER');

    const sameNrc = await addStudent(newStudent('300000003', { nrc: '300000/00/1' }));
    expect(sameNrc.status).toBe(409);
    expect(sameNrc.body.error).toBe('DUPLICATE_NRC');

    // Soft-deleted students keep their number AND NRC reserved.
    const doomed = await addStudent(newStudent('300000004'));
    await asLecturer(request(app).delete(`/api/students/${doomed.body.student.studentId}`));
    expect((await addStudent(newStudent('300000005', { nrc: nrcFor('300000004') }))).body.error).toBe('DUPLICATE_NRC');
    expect((await addStudent(newStudent('300000004', { nrc: '300000/77/7' }))).body.error).toBe('DUPLICATE_STUDENT_NUMBER');
  });

  test.each([
    ['a student number of the wrong length', { studentNumber: '12345' }, 'studentNumber'],
    ['a missing NRC', { nrc: undefined }, 'nrc'],
    ['an NRC in the wrong shape', { nrc: '12-34-56' }, 'nrc'],
    ['an NRC containing letters', { nrc: '30000A/01/1' }, 'nrc'],
    ['a missing date of birth', { dateOfBirth: undefined }, 'dateOfBirth'],
    ['a date of birth in the future', { dateOfBirth: '2999-12-31' }, 'dateOfBirth'],
    ['a date of birth that does not exist', { dateOfBirth: '2003-02-30' }, 'dateOfBirth'],
    ['a date of birth in the wrong format', { dateOfBirth: '17/05/2003' }, 'dateOfBirth'],
    ['a name containing digits', { fullName: 'G03 Filler 0' }, 'fullName'],
    ['a programme code in the wrong shape', { programmeCode: 'C$' }, 'programmeCode'],
  ])('%s is rejected and nothing is saved', async (_label, override, field) => {
    const before = await countStudents();
    const res = await addStudent(newStudent('300000009', override));
    expect(res.status).toBe(422);
    expect(res.body.error).toBe('VALIDATION_ERROR');
    expect(res.body.message).toContain(field);
    expect(await countStudents()).toBe(before);
  });

  test('a lecturer can correct a mistyped NRC or birth date, but not to someone else\'s NRC', async () => {
    const made = await addStudent(newStudent('300000006'));
    const id = made.body.student.studentId;

    const ok = await asLecturer(request(app).put(`/api/students/${id}`)).send({ nrc: '300000607', dateOfBirth: '2002-01-31' });
    expect(ok.status).toBe(200);
    expect(ok.body.student).toMatchObject({ nrc: '300000/60/7', dateOfBirth: '2002-01-31', version: 2 });

    const clash = await asLecturer(request(app).put(`/api/students/${id}`)).send({ nrc: '300000/00/1' });
    expect(clash.status).toBe(409);
    expect(clash.body.error).toBe('DUPLICATE_NRC');

    const bad = await asLecturer(request(app).put(`/api/students/${id}`)).send({ nrc: 'nonsense' });
    expect(bad.status).toBe(422);
  });
});

// ---------------------------------------------------------------------------
describe('who can see and change what', () => {
  let studentToken;
  let student;

  beforeAll(async () => {
    const signUp = await signUpStudent(app, outbox, { studentNumber: '202301010', email: 'given@example.com' });
    studentToken = signUp.body.token;
    student = signUp.body.student;
  });

  test('a student can read their own profile, including their own NRC and birth date', async () => {
    const res = await request(app).get('/api/students/me').set('Authorization', `Bearer ${studentToken}`);
    expect(res.status).toBe(200);
    expect(res.body.student).toMatchObject({ studentNumber: '202301010', nrc: '700010/10/1', dateOfBirth: '2002-08-05' });
  });

  test('a student cannot read another student\'s record, nor list the roster (enforced by role, not by hiding a button)', async () => {
    const [[other]] = await pool.query("SELECT student_id FROM students WHERE student_number = '202301001'");
    const one = await request(app).get(`/api/students/${other.student_id}`).set('Authorization', `Bearer ${studentToken}`);
    expect(one.status).toBe(403);
    const list = await request(app).get('/api/students').set('Authorization', `Bearer ${studentToken}`);
    expect(list.status).toBe(403);
    expect(list.body.error).toBe('FORBIDDEN');
  });

  test('a student cannot add, delete or edit others, or use lecturer-only offline operations', async () => {
    const asStudent = (req) => req.set('Authorization', `Bearer ${studentToken}`);
    expect((await asStudent(request(app).post('/api/students')).send(newStudent('300000020'))).status).toBe(403);
    expect((await asStudent(request(app).delete(`/api/students/${student.studentId}`))).status).toBe(403);
    const create = await asStudent(request(app).post('/api/students/mutate')).send({
      operationId: crypto.randomUUID(), type: 'CREATE', payload: newStudent('300000021'),
    });
    expect(create.status).toBe(403);
  });

  test('a student can edit their name and programme, but never their NRC, birth date or number', async () => {
    const res = await request(app)
      .put('/api/students/me')
      .set('Authorization', `Bearer ${studentToken}`)
      .send({ fullName: 'Given C. Chileshe', programmeCode: 'DS', nrc: '999999/99/9', dateOfBirth: '1990-01-01', studentNumber: '111111111' });
    expect(res.status).toBe(200);
    expect(res.body.student).toMatchObject({ fullName: 'Given C. Chileshe', programmeCode: 'DS', nrc: '700010/10/1', dateOfBirth: '2002-08-05', studentNumber: '202301010' });

    // The same through the offline-sync EDIT: identity fields are ignored for a student.
    const offline = await request(app)
      .post('/api/students/mutate')
      .set('Authorization', `Bearer ${studentToken}`)
      .send({ operationId: crypto.randomUUID(), type: 'EDIT', payload: { fullName: 'Given Chileshe', nrc: '999999/99/9', dateOfBirth: '1990-01-01' } });
    expect(offline.status).toBe(200);
    expect(offline.body.record).toMatchObject({ fullName: 'Given Chileshe', nrc: '700010/10/1', dateOfBirth: '2002-08-05' });
  });

  test('number correction: PENDING, then APPROVED changes the number but not the identity — and the new number signs in', async () => {
    const submit = await request(app)
      .post('/api/number-correction-requests')
      .set('Authorization', `Bearer ${studentToken}`)
      .send({ newNumber: '202301777', reason: 'Typo at registration' });
    expect(submit.status).toBe(201);
    expect(submit.body.status).toBe('PENDING');

    const approve = await asLecturer(request(app).put(`/api/number-correction-requests/${submit.body.requestId}/approve`));
    expect(approve.status).toBe(200);
    expect(approve.body.status).toBe('APPROVED');

    const after = await request(app).get('/api/students/me').set('Authorization', `Bearer ${studentToken}`);
    expect(after.body.student.studentNumber).toBe('202301777');
    expect(after.body.student.studentId).toBe(student.studentId); // identity unchanged
    expect(after.body.student.nrc).toBe('700010/10/1'); // the NRC is what ties them to one real person

    expect((await request(app).post('/api/auth/login').send({ loginKey: '202301777', password: 'Str0ngPass!' })).status).toBe(200);
    expect((await request(app).post('/api/auth/login').send({ loginKey: '202301010', password: 'Str0ngPass!' })).status).toBe(401);
  });
});

// ---------------------------------------------------------------------------
describe('soft delete', () => {
  test('the row stays, the seat is released once, and the person disappears from the API', async () => {
    const [[target]] = await pool.query("SELECT student_id FROM students WHERE student_number = '202301015'"); // in G02
    const seats = async () => (await pool.query("SELECT COUNT(*) AS n FROM students s JOIN lab_groups g ON g.group_id = s.group_id WHERE g.group_code = 'G02' AND s.active = 1"))[0][0].n;
    const before = await seats();

    const del = await asLecturer(request(app).delete(`/api/students/${target.student_id}`));
    expect(del.status).toBe(200);

    const [[row]] = await pool.query('SELECT active, deleted_at, group_id FROM students WHERE student_id = ?', [target.student_id]);
    expect(row.active).toBe(0);
    expect(row.deleted_at).not.toBeNull();
    expect(row.group_id).toBeNull();
    expect(await seats()).toBe(before - 1);

    expect((await asLecturer(request(app).get(`/api/students/${target.student_id}`))).status).toBe(404);
    expect((await asLecturer(request(app).delete(`/api/students/${target.student_id}`))).status).toBe(404); // not released twice
    expect(await seats()).toBe(before - 1);
  });
});

// ---------------------------------------------------------------------------
describe('retries and offline operations (idempotency)', () => {
  test('retrying the same operationId does not apply the change twice; reusing it for different content is refused', async () => {
    const made = await addStudent(newStudent('300000030', { fullName: 'Retry Target' }));
    const studentId = made.body.student.studentId;
    const operationId = crypto.randomUUID();
    const assign = (groupCode) => asLecturer(request(app).post(`/api/students/${studentId}/group`)).send({ groupCode, operationId });

    const first = await assign('G02');
    expect(first.status).toBe(200);
    const retry = await assign('G02');
    expect(retry.status).toBe(200);
    expect(retry.body).toEqual(first.body); // replayed, not re-executed

    const [[{ n }]] = await pool.query(
      "SELECT COUNT(*) AS n FROM students WHERE student_id = ? AND group_id = (SELECT group_id FROM lab_groups WHERE group_code = 'G02')",
      [studentId]
    );
    expect(n).toBe(1);

    const reused = await assign('G03'); // same id, DIFFERENT content
    expect(reused.status).toBe(422);
    expect(reused.body.error).toBe('OPERATION_ID_REUSED');
  });

  test('offline EDIT and DELETE obey exactly the same rules as the REST endpoints', async () => {
    const made = await addStudent(newStudent('300000050', { fullName: 'Offline Target' }));
    const { studentId } = made.body.student;
    const send = (body) => asLecturer(request(app).post('/api/students/mutate')).send({ operationId: crypto.randomUUID(), ...body });

    // The same name check as PUT /api/students/:id (the offline copy used to skip it).
    const badName = await send({ type: 'EDIT', studentId, payload: { fullName: 'R2-D2 <script>' } });
    expect(badName.status).toBe(422);
    expect(badName.body.error).toBe('VALIDATION_ERROR');

    // A lecturer may correct the NRC offline too, and a stale baseVersion is a conflict.
    const fixed = await send({ type: 'EDIT', studentId, baseVersion: 1, payload: { nrc: '300000519' } });
    expect(fixed.status).toBe(200);
    expect(fixed.body.record).toMatchObject({ nrc: '300000/51/9', version: 2 });
    const stale = await send({ type: 'EDIT', studentId, baseVersion: 1, payload: { fullName: 'Too Late' } });
    expect(stale.status).toBe(409);
    expect(stale.body.error).toBe('VERSION_CONFLICT');

    const gone = await send({ type: 'DELETE', studentId });
    expect(gone.status).toBe(200);
    expect(gone.body.record).toBeNull();
    const [[row]] = await pool.query('SELECT active, deleted_at FROM students WHERE student_id = ?', [studentId]);
    expect(row.active).toBe(0);
    expect(row.deleted_at).not.toBeNull();
    const again = await send({ type: 'DELETE', studentId });
    expect(again.status).toBe(404);
  });

  test('an offline-queued CREATE carries NRC and birth date, is validated, and replays safely', async () => {
    const operationId = crypto.randomUUID();
    const send = (id, payload) => asLecturer(request(app).post('/api/students/mutate')).send({ operationId: id, type: 'CREATE', payload });

    const first = await send(operationId, newStudent('300000040', { fullName: 'Offline Created' }));
    expect(first.status).toBe(200);
    expect(first.body.status).toBe('APPLIED');
    expect(first.body.record).toMatchObject({ studentNumber: '300000040', nrc: '300000/04/0', dateOfBirth: '2003-05-17' });

    const replay = await send(operationId, newStudent('300000040', { fullName: 'Offline Created' }));
    expect(replay.body).toEqual(first.body);
    expect((await pool.query("SELECT COUNT(*) AS n FROM students WHERE student_number = '300000040'"))[0][0].n).toBe(1);

    const noNrc = await send(crypto.randomUUID(), newStudent('300000041', { nrc: undefined }));
    expect(noNrc.status).toBe(422);
    const dupNrc = await send(crypto.randomUUID(), newStudent('300000042', { nrc: '300000/04/0' }));
    expect(dupNrc.status).toBe(409);
    expect(dupNrc.body.error).toBe('DUPLICATE_NRC');
  });
});
