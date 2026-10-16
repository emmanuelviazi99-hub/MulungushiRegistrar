// tests/first-sign-in.test.js
//
// "Welcome, Riley!" on the first sign-in, "Welcome back, Riley!" after that.
// The server decides (accounts.first_signed_in_at) and says so in every sign-in
// answer as firstSignIn. A finished sign-up is the first sign-in. Also: a student
// cannot ask for the group they are already in (ALREADY_IN_GROUP), and an
// unassigned student's request uses the same request kind as a group change.

process.env.NODE_ENV = 'test';

const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, signUpStudent, identityOf, lastCodeFor } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');

const login = (loginKey, password) => request(app).post('/api/auth/login').send({ loginKey, password });

beforeAll(async () => {
  await resetDb();
});

afterAll(async () => {
  await pool.end();
});

describe('first sign-in', () => {
  test('a finished sign-up is the first sign-in; every later sign-in is a return', async () => {
    const done = await signUpStudent(app, outbox, { studentNumber: '202301003', email: 'riley@test.example', password: 'Riley#Pass1' });
    expect(done.body.firstSignIn).toBe(true);

    const second = await login('202301003', 'Riley#Pass1');
    expect(second.status).toBe(200);
    expect(second.body.firstSignIn).toBe(false);

    // signing out is only the phone forgetting its token: the next sign-in is still a return
    const third = await login('riley@test.example', 'Riley#Pass1');
    expect(third.body.firstSignIn).toBe(false);
  });

  test('an account made before its first sign-in (e.g. a seeded lecturer) is greeted as new once', async () => {
    const lecturer = await createLecturer(pool, { email: 'new.lecturer@test.example', employeeId: 'EMP-9701' });
    const first = await login(lecturer.email, lecturer.password);
    expect(first.status).toBe(200);
    expect(first.body.firstSignIn).toBe(true);
    const again = await login(lecturer.email, lecturer.password);
    expect(again.body.firstSignIn).toBe(false);
  });

  test('a failed sign-in does not use up the first welcome', async () => {
    const lecturer = await createLecturer(pool, { email: 'careful@test.example', employeeId: 'EMP-9702', password: 'Right#Pass1' });
    const wrong = await login(lecturer.email, 'not-it');
    expect(wrong.status).toBe(401);
    expect(wrong.body.firstSignIn).toBeUndefined();
    const right = await login(lecturer.email, 'Right#Pass1');
    expect(right.body.firstSignIn).toBe(true);
  });

  test('two sign-ins at the same moment: exactly one is "first"', async () => {
    const lecturer = await createLecturer(pool, { email: 'twice@test.example', employeeId: 'EMP-9703', password: 'Twice#Pass1' });
    const both = await Promise.all([login(lecturer.email, 'Twice#Pass1'), login(lecturer.email, 'Twice#Pass1')]);
    expect(both.map((r) => r.status)).toEqual([200, 200]);
    expect(both.filter((r) => r.body.firstSignIn === true)).toHaveLength(1);
  });

  test('a lecturer sign-up is a first sign-in too', async () => {
    await pool.query("INSERT INTO staff_roster (employee_id, email, full_name) VALUES ('EMP-9704', 'staff.new@test.example', 'Staff New')");
    const asked = await request(app).post('/api/auth/staff/request').send({ fullName: 'Staff New', email: 'staff.new@test.example', employeeId: 'EMP-9704' });
    expect(asked.status).toBe(200);
    const code = lastCodeFor(outbox, 'staff.new@test.example');
    expect((await request(app).post('/api/auth/staff/verify').send({ email: 'staff.new@test.example', code })).status).toBe(200);
    const done = await request(app).post('/api/auth/staff/complete').send({ email: 'staff.new@test.example', password: 'Staff#Pass1' });
    expect(done.status).toBe(201);
    expect(done.body.firstSignIn).toBe(true);
    expect((await login('staff.new@test.example', 'Staff#Pass1')).body.firstSignIn).toBe(false);
  });

  test('changing the password is not a new sign-in', async () => {
    const token = (await login('202301003', 'Riley#Pass1')).body.token;
    const changed = await request(app).put('/api/auth/password').set('Authorization', `Bearer ${token}`)
      .send({ currentPassword: 'Riley#Pass1', newPassword: 'Riley#Pass2' });
    expect(changed.status).toBe(200);
    expect(changed.body.firstSignIn).toBe(false);
  });
});

describe('group requests and the current group', () => {
  let token;
  const ask = (groupCode) => request(app).post('/api/group-requests').set('Authorization', `Bearer ${token}`).send({ groupCode });

  beforeAll(async () => {
    // 202301018 is seeded Unassigned
    const done = await signUpStudent(app, outbox, { studentNumber: '202301018', email: 'unassigned@test.example' });
    token = done.body.token;
    expect(done.body.student.groupCode).toBeNull();
  });

  test('an unassigned student asks for a first group with the same request kind (from: no group)', async () => {
    const res = await ask('G02');
    expect(res.status).toBe(201);
    const [[open]] = await pool.query(
      "SELECT r.kind, r.from_group_id FROM change_requests r JOIN students s ON s.student_id = r.student_id WHERE s.student_number = '202301018' AND r.status = 'PENDING'"
    );
    expect(open.kind).toBe('GROUP');
    expect(open.from_group_id).toBeNull();
    // withdraw it for the next test
    const cancel = await request(app).delete('/api/my-requests/group').set('Authorization', `Bearer ${token}`);
    expect(cancel.status).toBe(200);
  });

  test('asking for the group you are already in is refused and nothing is stored', async () => {
    await pool.query(
      "UPDATE students SET group_id = (SELECT group_id FROM lab_groups WHERE group_code = 'G03'), version = version + 1 WHERE student_number = '202301018'"
    );
    const res = await ask('G03');
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('ALREADY_IN_GROUP');
    const [[{ n }]] = await pool.query(
      "SELECT COUNT(*) AS n FROM change_requests r JOIN students s ON s.student_id = r.student_id WHERE s.student_number = '202301018' AND r.status = 'PENDING'"
    );
    expect(n).toBe(0);

    const change = await ask('G04'); // a real change still works
    expect(change.status).toBe(201);
  });
});
