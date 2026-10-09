// tests/auth.test.js
//
// Covers the brief's Test 1-8 and Test 13-15.

process.env.NODE_ENV = 'test';

const bcrypt = require('bcrypt');
const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');

let lecturerToken;

beforeAll(async () => {
  await resetDb();
  const passwordHash = await bcrypt.hash('TestPass#1', 12);
  await pool.query('INSERT INTO accounts (username, password_hash, role, student_id) VALUES (?, ?, ?, NULL)', [
    'lecturer1',
    passwordHash,
    'LECTURER',
  ]);
  const login = await request(app).post('/api/auth/login').send({ username: 'lecturer1', password: 'TestPass#1' });
  lecturerToken = login.body.token;
});

afterAll(async () => {
  await pool.end();
});

test('Test 1 — valid registration with a valid claim code succeeds', async () => {
  const res = await request(app).post('/api/auth/register').send({
    studentNumber: '202301001',
    claimCode: 'MU-7K4Q', // from database/seed.sql
    username: 'chanda.m',
    password: 'Str0ngPass!',
  });
  expect(res.status).toBe(201);
  expect(res.body.role).toBe('STUDENT');
  expect(res.body.token).toBeDefined();
});

test('Test 2 — an invalid claim code is rejected', async () => {
  const res = await request(app).post('/api/auth/register').send({
    studentNumber: '202301002',
    claimCode: 'MU-WRNG',
    username: 'bwalya.p',
    password: 'Str0ngPass!',
  });
  expect(res.status).toBe(401);
  expect(res.body.error).toBe('INVALID_CLAIM_CODE');
});

test('Test 3 — a claim code that was already used is rejected', async () => {
  // 202301003 (Mutinta Banda) has a pre-used code "MU-OLD1" in seed.sql.
  const res = await request(app).post('/api/auth/register').send({
    studentNumber: '202301003',
    claimCode: 'MU-OLD1',
    username: 'mutinta.b',
    password: 'Str0ngPass!',
  });
  expect(res.status).toBe(409);
  expect(res.body.error).toBe('CLAIM_ALREADY_USED');
});

test('Test 3b — an expired claim code is rejected', async () => {
  // 202301004 (Natasha Zulu) has an already-expired code "MU-EXP1" in seed.sql.
  const res = await request(app).post('/api/auth/register').send({
    studentNumber: '202301004',
    claimCode: 'MU-EXP1',
    username: 'natasha.z',
    password: 'Str0ngPass!',
  });
  expect(res.status).toBe(410);
  expect(res.body.error).toBe('CLAIM_EXPIRED');
});

test('Test 4 — a duplicate student number is rejected on creation', async () => {
  const res = await request(app)
    .post('/api/students')
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ fullName: 'Someone Else', studentNumber: '202301001', programmeCode: 'CS' });
  expect(res.status).toBe(409);
  expect(res.body.error).toBe('DUPLICATE_STUDENT_NUMBER');
});

test('Test 5 — an invalid student number is rejected', async () => {
  const res = await request(app)
    .post('/api/students')
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ fullName: 'Bad Number', studentNumber: '12345', programmeCode: 'CS' });
  expect(res.status).toBe(422);
  expect(res.body.error).toBe('VALIDATION_ERROR');
});

test('Test 6 — a student can access their own profile', async () => {
  const register = await request(app).post('/api/auth/register').send({
    studentNumber: '202301018',
    claimCode: 'MU-3H8Z',
    username: 'innocent.m',
    password: 'Str0ngPass!',
  });
  const studentToken = register.body.token;

  const res = await request(app).get('/api/students/me').set('Authorization', `Bearer ${studentToken}`);
  expect(res.status).toBe(200);
  expect(res.body.student.studentNumber).toBe('202301018');
});

test('Test 7 — a student cannot access another student\'s record via the lecturer endpoint', async () => {
  const register = await request(app).post('/api/auth/register').send({
    studentNumber: '202301002',
    claimCode: 'MU-9P2X',
    username: 'bwalya.p2',
    password: 'Str0ngPass!',
  });
  const studentToken = register.body.token;

  const [[other]] = await pool.query("SELECT student_id FROM students WHERE student_number = '202301001'");

  const res = await request(app)
    .get(`/api/students/${other.student_id}`)
    .set('Authorization', `Bearer ${studentToken}`);
  expect(res.status).toBe(403); // lecturer-only endpoint, enforced by role, not by hiding a button
});

test('Test 8 — a student hitting a lecturer-only endpoint gets 403 FORBIDDEN', async () => {
  const login = await request(app)
    .post('/api/auth/login')
    .send({ username: 'chanda.m', password: 'Str0ngPass!' }); // created in Test 1
  const studentToken = login.body.token;

  const res = await request(app).get('/api/students').set('Authorization', `Bearer ${studentToken}`);
  expect(res.status).toBe(403);
  expect(res.body.error).toBe('FORBIDDEN');
});

test('Unauthenticated requests are rejected with 401', async () => {
  const res = await request(app).get('/api/students/me');
  expect(res.status).toBe(401);
  expect(res.body.error).toBe('UNAUTHORIZED');
});

test('Test 13/14 — number correction request becomes PENDING, then APPROVED changes the number but not the id', async () => {
  const login = await request(app)
    .post('/api/auth/login')
    .send({ username: 'innocent.m', password: 'Str0ngPass!' }); // created in Test 6
  const studentToken = login.body.token;

  const before = await request(app).get('/api/students/me').set('Authorization', `Bearer ${studentToken}`);
  const studentId = before.body.student.studentId;

  const submit = await request(app)
    .post('/api/number-correction-requests')
    .set('Authorization', `Bearer ${studentToken}`)
    .send({ newNumber: '202301777', reason: 'Typo at registration' });
  expect(submit.status).toBe(201);
  expect(submit.body.status).toBe('PENDING');

  const approve = await request(app)
    .put(`/api/number-correction-requests/${submit.body.requestId}/approve`)
    .set('Authorization', `Bearer ${lecturerToken}`);
  expect(approve.status).toBe(200);
  expect(approve.body.status).toBe('APPROVED');

  const after = await request(app).get('/api/students/me').set('Authorization', `Bearer ${studentToken}`);
  expect(after.body.student.studentNumber).toBe('202301777');
  expect(after.body.student.studentId).toBe(studentId); // identity unchanged
});

test('Test 15 — deleting a student soft-deletes it (record remains, not removed)', async () => {
  const [[toDelete]] = await pool.query("SELECT student_id FROM students WHERE student_number = '202301015'");

  const del = await request(app)
    .delete(`/api/students/${toDelete.student_id}`)
    .set('Authorization', `Bearer ${lecturerToken}`);
  expect(del.status).toBe(200);

  const [[row]] = await pool.query('SELECT active, deleted_at FROM students WHERE student_id = ?', [
    toDelete.student_id,
  ]);
  expect(row.active).toBe(0);
  expect(row.deleted_at).not.toBeNull();

  // The API hides it from normal lookups, but the row is still in MySQL.
  const getViaApi = await request(app)
    .get(`/api/students/${toDelete.student_id}`)
    .set('Authorization', `Bearer ${lecturerToken}`);
  expect(getViaApi.status).toBe(404);

  // Repeating the delete releases the seat only once (no error masking a real re-delete).
  const delAgain = await request(app)
    .delete(`/api/students/${toDelete.student_id}`)
    .set('Authorization', `Bearer ${lecturerToken}`);
  expect(delAgain.status).toBe(404);
});

test('Challenge 2 — retrying the same operationId does not double-apply the effect', async () => {
  const res = await request(app)
    .post('/api/students')
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ fullName: 'Retry Target', studentNumber: '202301500', programmeCode: 'CS' });
  const studentId = res.body.student.studentId;
  const operationId = '11111111-1111-1111-1111-111111111111';

  const first = await request(app)
    .post(`/api/students/${studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G02', operationId });
  expect(first.status).toBe(200);

  const retry = await request(app)
    .post(`/api/students/${studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G02', operationId }); // same id, same body
  expect(retry.status).toBe(200);
  expect(retry.body).toEqual(first.body); // replayed, not re-executed

  const [[{ count }]] = await pool.query(
    "SELECT COUNT(*) AS count FROM students WHERE student_id = ? AND group_id = (SELECT group_id FROM lab_groups WHERE group_code = 'G02')",
    [studentId]
  );
  expect(count).toBe(1); // still just the one membership, not applied twice

  const reused = await request(app)
    .post(`/api/students/${studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G03', operationId }); // same id, DIFFERENT body
  expect(reused.status).toBe(422);
  expect(reused.body.error).toBe('OPERATION_ID_REUSED');
});
