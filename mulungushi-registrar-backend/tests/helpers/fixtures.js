// tests/helpers/fixtures.js
//
// Shared helpers for the test files. The student identities below mirror
// database/seed.sql (18 students, NRC 7000NN/10/1, the birth dates listed there).

const bcrypt = require('bcrypt');
const request = require('supertest');

const SEED_DOBS = [
  '2003-04-12', '2002-11-03', '2003-01-25', '2002-07-19', '2003-09-30', '2002-02-14',
  '2003-06-08', '2002-12-21', '2003-03-17', '2002-08-05', '2003-10-26', '2002-05-11',
  '2003-07-02', '2002-09-18', '2003-12-09', '2002-03-23', '2003-05-30', '2002-10-15',
];

/** The NRC + date of birth a seeded student (202301001..018) must type to sign up. */
function identityOf(studentNumber) {
  const n = Number(String(studentNumber).slice(-2));
  return { nrc: `7000${String(n).padStart(2, '0')}/10/1`, dateOfBirth: SEED_DOBS[n - 1] };
}

/** A unique, valid NRC derived from a unique 9-digit number (for students the tests create). */
function nrcFor(studentNumber) {
  const s = String(studentNumber);
  return `${s.slice(0, 6)}/${s.slice(6, 8)}/${s.slice(8)}`;
}

async function createLecturer(pool, { email = 'lecturer@test.example', employeeId = 'EMP-9001', name = 'Test Lecturer', password = 'TestPass#1' } = {}) {
  const [staff] = await pool.query('INSERT INTO staff_roster (employee_id, email, full_name) VALUES (?, ?, ?)', [employeeId, email, name]);
  const hash = await bcrypt.hash(password, 4);
  await pool.query("INSERT INTO accounts (email, password_hash, staff_id) VALUES (?, ?, ?)", [email, hash, staff.insertId]);
  return { email, password };
}

async function loginAs(app, loginKey, password) {
  const res = await request(app).post('/api/auth/login').send({ loginKey, password });
  expect(res.status).toBe(200);
  return res.body.token;
}

/** The code most recently "sent" to this email (the test outbox stands in for email/SMS). */
function lastCodeFor(outbox, email) {
  const sent = outbox.filter((m) => m.to === email.toLowerCase());
  expect(sent.length).toBeGreaterThan(0);
  return sent[sent.length - 1].code;
}

/** Runs the whole student sign-up conversation and returns the final response. */
async function signUpStudent(app, outbox, { studentNumber, email, password = 'Str0ngPass!', phone = '0977123456', programmeCode }) {
  const { nrc, dateOfBirth } = identityOf(studentNumber);
  const asked = await request(app).post('/api/auth/register/request').send({ fullName: 'Test Student', studentNumber, nrc, dateOfBirth, email, phone });
  expect(asked.status).toBe(200);
  const code = lastCodeFor(outbox, email);
  const verified = await request(app).post('/api/auth/register/verify').send({ email, code });
  expect(verified.status).toBe(200);
  const done = await request(app).post('/api/auth/register/complete').send({ email, password, programmeCode });
  expect(done.status).toBe(201);
  return done;
}

module.exports = { identityOf, nrcFor, createLecturer, loginAs, lastCodeFor, signUpStudent };
