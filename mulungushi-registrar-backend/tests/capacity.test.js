// tests/capacity.test.js
//
// Covers the brief's Test 9, Test 10, Test 11, Test 12 and Challenge 1.
//
// Run with:  npm test
// Requires TEST_DB_NAME (see .env.example / tests/helpers/resetDb.js) to
// point at a MySQL database that is safe to drop and recreate.

process.env.NODE_ENV = 'test';

const bcrypt = require('bcrypt');
const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');

// IMPORTANT: point the app's own connection pool at the test database
// BEFORE requiring anything that imports config/database.js.
process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');

let lecturerToken;

async function loginAsLecturer() {
  const res = await request(app).post('/api/auth/login').send({ username: 'lecturer1', password: 'TestPass#1' });
  expect(res.status).toBe(200);
  return res.body.token;
}

async function createUnassignedStudent(studentNumber, name) {
  const res = await request(app)
    .post('/api/students')
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ fullName: name, studentNumber, programmeCode: 'CS' }); // no groupCode -> Unassigned
  expect(res.status).toBe(201);
  return res.body.student;
}

beforeAll(async () => {
  await resetDb();

  // Seed a lecturer account directly (seed.sql deliberately has no accounts,
  // see database/seed-accounts.js's comment for why).
  const passwordHash = await bcrypt.hash('TestPass#1', 12);
  await pool.query('INSERT INTO accounts (username, password_hash, role, student_id) VALUES (?, ?, ?, NULL)', [
    'lecturer1',
    passwordHash,
    'LECTURER',
  ]);

  lecturerToken = await loginAsLecturer();
});

afterAll(async () => {
  await pool.end();
});

test('Test 9 — a group with 14 active students can accept one more', async () => {
  const student = await createUnassignedStudent('900000014', 'Fourteenth Candidate');

  const res = await request(app)
    .post(`/api/students/${student.studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G01' });

  expect(res.status).toBe(200);
  expect(res.body.student.groupCode).toBe('G01');

  const [[{ activeCount }]] = await pool.query(
    `SELECT COUNT(*) AS activeCount FROM students s JOIN lab_groups g ON g.group_id = s.group_id
     WHERE g.group_code = 'G01' AND s.active = 1`
  );
  expect(activeCount).toBe(15);
});

test('Test 10 — a full group (15) rejects a new assignment with GROUP_FULL', async () => {
  // G01 is now full from the previous test's effect persisting in the same
  // test DB within this file — but to keep this test independent, fill it
  // explicitly here too in case tests run in isolation.
  const filler = await createUnassignedStudent('900000015', 'Fifteenth Candidate');
  await request(app)
    .post(`/api/students/${filler.studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G01' });

  const extra = await createUnassignedStudent('900000016', 'One Too Many');
  const res = await request(app)
    .post(`/api/students/${extra.studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G01' });

  expect(res.status).toBe(409);
  expect(res.body.error).toBe('GROUP_FULL');

  const [[{ activeCount }]] = await pool.query(
    `SELECT COUNT(*) AS activeCount FROM students s JOIN lab_groups g ON g.group_id = s.group_id
     WHERE g.group_code = 'G01' AND s.active = 1`
  );
  expect(activeCount).toBeLessThanOrEqual(15);
});

test('Test 12 — a failed transfer leaves the student in their previous group', async () => {
  const student = await createUnassignedStudent('900000020', 'Stays Put');
  await request(app)
    .post(`/api/students/${student.studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G02' });

  // G01 should already be full from the earlier tests in this file.
  const res = await request(app)
    .post(`/api/students/${student.studentId}/group`)
    .set('Authorization', `Bearer ${lecturerToken}`)
    .send({ groupCode: 'G01' });

  expect(res.status).toBe(409);

  const check = await request(app)
    .get(`/api/students/${student.studentId}`)
    .set('Authorization', `Bearer ${lecturerToken}`);
  expect(check.body.student.groupCode).toBe('G02'); // unchanged
});

test('Challenge 1 / Test 11 — exactly one of two simultaneous requests for the last place succeeds', async () => {
  // Start a FRESH group with exactly 14 active members so there is
  // precisely one seat left, then race two different students for it.
  const [[group]] = await pool.query("SELECT group_id FROM lab_groups WHERE group_code = 'G03'");
  const [[{ activeCount: startingCount }]] = await pool.query(
    'SELECT COUNT(*) AS activeCount FROM students WHERE group_id = ? AND active = 1',
    [group.group_id]
  );
  const seatsToFill = 14 - startingCount;
  for (let i = 0; i < seatsToFill; i++) {
    const s = await createUnassignedStudent(`91${String(i).padStart(7, '0')}`, `G03 Filler ${i}`);
    await request(app)
      .post(`/api/students/${s.studentId}/group`)
      .set('Authorization', `Bearer ${lecturerToken}`)
      .send({ groupCode: 'G03' });
  }

  const clientA = await createUnassignedStudent('920000001', 'Client A');
  const clientB = await createUnassignedStudent('920000002', 'Client B');

  const [resA, resB] = await Promise.all([
    request(app)
      .post(`/api/students/${clientA.studentId}/group`)
      .set('Authorization', `Bearer ${lecturerToken}`)
      .send({ groupCode: 'G03' }),
    request(app)
      .post(`/api/students/${clientB.studentId}/group`)
      .set('Authorization', `Bearer ${lecturerToken}`)
      .send({ groupCode: 'G03' }),
  ]);

  const statuses = [resA.status, resB.status].sort();
  expect(statuses).toEqual([200, 409]);

  const [[{ finalCount }]] = await pool.query(
    'SELECT COUNT(*) AS finalCount FROM students WHERE group_id = ? AND active = 1',
    [group.group_id]
  );
  expect(finalCount).toBe(15); // never 16
});

test('Challenge 1, repeated — 20 resets confirm the group never exceeds capacity', async () => {
  for (let run = 0; run < 20; run++) {
    await pool.query("DELETE FROM students WHERE student_number LIKE '93%'");
    const [[group]] = await pool.query("SELECT group_id, capacity FROM lab_groups WHERE group_code = 'G04'");
    await pool.query('UPDATE students SET group_id = NULL WHERE group_id = ?', [group.group_id]);

    for (let i = 0; i < 14; i++) {
      const s = await createUnassignedStudent(`93${run}${String(i).padStart(6, '0')}`, `Run${run} Filler${i}`);
      await request(app)
        .post(`/api/students/${s.studentId}/group`)
        .set('Authorization', `Bearer ${lecturerToken}`)
        .send({ groupCode: 'G04' });
    }

    const lastA = await createUnassignedStudent(`94${run}0000001`, `Run${run} A`);
    const lastB = await createUnassignedStudent(`94${run}0000002`, `Run${run} B`);

    const [a, b] = await Promise.all([
      request(app)
        .post(`/api/students/${lastA.studentId}/group`)
        .set('Authorization', `Bearer ${lecturerToken}`)
        .send({ groupCode: 'G04' }),
      request(app)
        .post(`/api/students/${lastB.studentId}/group`)
        .set('Authorization', `Bearer ${lecturerToken}`)
        .send({ groupCode: 'G04' }),
    ]);

    expect([a.status, b.status].sort()).toEqual([200, 409]);

    const [[{ finalCount }]] = await pool.query(
      'SELECT COUNT(*) AS finalCount FROM students WHERE group_id = ? AND active = 1',
      [group.group_id]
    );
    expect(finalCount).toBe(15);
  }
});
