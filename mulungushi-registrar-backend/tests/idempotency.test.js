// tests/idempotency.test.js
//
// The app's offline queue gives every change a UUID and sends it as an
// Idempotency-Key header each time it tries to upload. These tests prove a
// change is applied exactly once however many times it is sent: the answer
// to a retry is the answer the first try got (middleware/idempotency.js).

process.env.NODE_ENV = 'test';

const request = require('supertest');
const crypto = require('crypto');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, loginAs, signUpStudent } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');

let lecturerToken;
let otherLecturerToken;
const tokens = {};
const ids = {};
const key = () => crypto.randomUUID();
const asLecturer = (req, k) => {
  req.set('Authorization', `Bearer ${lecturerToken}`);
  return k ? req.set('Idempotency-Key', k) : req;
};
const as = (who, req, k) => {
  req.set('Authorization', `Bearer ${tokens[who]}`);
  return k ? req.set('Idempotency-Key', k) : req;
};
const current = async (id) => (await asLecturer(request(app).get(`/api/students/${id}`))).body.student;

beforeAll(async () => {
  await resetDb();
  const lecturer = await createLecturer(pool, { employeeId: 'EMP-9701' });
  lecturerToken = await loginAs(app, lecturer.email, lecturer.password);
  const other = await createLecturer(pool, { email: 'other.lecturer@test.example', employeeId: 'EMP-9702' });
  otherLecturerToken = await loginAs(app, other.email, other.password);
  // Seeded: 202301016 is in G03, 017 in G04, 018 Unassigned; G01 holds 14/15.
  for (const n of ['016', '017', '018']) {
    const done = await signUpStudent(app, outbox, { studentNumber: `202301${n}`, email: `i${n}@test.example` });
    tokens[n] = done.body.token;
    ids[n] = done.body.student.studentId;
  }
});

afterAll(async () => {
  await pool.end();
});

describe('a retried change is applied once, and the retry gets the first answer', () => {
  test("the lecturer's save, sent twice (the answer was lost): one change, not a VERSION_CONFLICT", async () => {
    const before = await current(ids['016']);
    const k = key();
    const body = { fullName: 'Joseph Retry Phiri', version: before.version };

    const first = await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send(body);
    const retry = await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send(body);

    expect(first.status).toBe(200);
    expect(retry.status).toBe(200);
    expect(retry.headers['idempotent-replayed']).toBe('true');
    expect(retry.body).toEqual(first.body);
    const after = await current(ids['016']);
    expect(after.fullName).toBe('Joseph Retry Phiri');
    expect(after.version).toBe(before.version + 1); // changed once
  });

  test('without the key the same retry IS a conflict (why the app always sends one)', async () => {
    const before = await current(ids['016']);
    const body = { fullName: 'Joseph Phiri', version: before.version };
    const first = await asLecturer(request(app).put(`/api/students/${ids['016']}`)).send(body);
    const retry = await asLecturer(request(app).put(`/api/students/${ids['016']}`)).send(body);
    expect(first.status).toBe(200);
    expect(retry.status).toBe(409);
    expect(retry.body.error).toBe('VERSION_CONFLICT');
  });

  test('a group request sent three times: one request, the same requestId each time', async () => {
    const k = key();
    const answers = [];
    for (let i = 0; i < 3; i++) {
      answers.push(await as('018', request(app).post('/api/group-requests'), k).send({ groupCode: 'G02' }));
    }
    expect(answers.map((a) => a.status)).toEqual([201, 201, 201]);
    expect(new Set(answers.map((a) => a.body.requestId)).size).toBe(1);
    const [rows] = await pool.query(
      "SELECT COUNT(*) AS n FROM change_requests WHERE student_id = ? AND kind = 'GROUP'",
      [ids['018']]
    );
    expect(rows[0].n).toBe(1);
  });

  test('an approval sent twice: approved once, and the retry says APPROVED (not REQUEST_NOT_FOUND)', async () => {
    const queue = await asLecturer(request(app).get('/api/group-requests'));
    const open = queue.body.requests.find((r) => r.studentId === ids['018']);
    const g02 = async () =>
      (await asLecturer(request(app).get('/api/groups'))).body.groups.find((g) => g.groupCode === 'G02').activeCount;
    const before = await g02();
    const k = key();
    const first = await asLecturer(request(app).put(`/api/group-requests/${open.requestId}/approve`), k).send();
    const retry = await asLecturer(request(app).put(`/api/group-requests/${open.requestId}/approve`), k).send();
    expect(first.status).toBe(200);
    expect(retry.status).toBe(200);
    expect(retry.body.status).toBe('APPROVED');
    expect((await current(ids['018'])).groupCode).toBe('G02');
    expect(await g02()).toBe(before + 1); // counted once
  });

  test('adding a student, sent twice: one new student, the retry returns the same one', async () => {
    const k = key();
    const body = { fullName: 'Retry Twice', studentNumber: '202388001', nrc: '788001/10/1', dateOfBirth: '2004-01-02', programmeCode: 'CS' };
    const first = await asLecturer(request(app).post('/api/students'), k).send(body);
    const retry = await asLecturer(request(app).post('/api/students'), k).send(body);
    expect(first.status).toBe(201);
    expect(retry.status).toBe(201);
    expect(retry.body.student.studentId).toBe(first.body.student.studentId);
    const [rows] = await pool.query("SELECT COUNT(*) AS n FROM students WHERE student_number = '202388001'");
    expect(rows[0].n).toBe(1);
  });

  test('a delete sent twice: the retry says it worked (not STUDENT_NOT_FOUND)', async () => {
    const made = await asLecturer(request(app).post('/api/students')).send({
      fullName: 'Delete Twice', studentNumber: '202388002', nrc: '788002/10/1', dateOfBirth: '2004-01-03', programmeCode: 'IT',
    });
    const k = key();
    const first = await asLecturer(request(app).delete(`/api/students/${made.body.student.studentId}`), k);
    const retry = await asLecturer(request(app).delete(`/api/students/${made.body.student.studentId}`), k);
    expect(first.status).toBe(200);
    expect(retry.status).toBe(200);
    expect(retry.headers['idempotent-replayed']).toBe('true');
  });

  test('a refusal is remembered too: a retried GROUP_FULL is answered GROUP_FULL, nothing moves', async () => {
    // Fill G01 to 15 with 017, then try to move 016 in.
    const s17 = await current(ids['017']);
    await asLecturer(request(app).put(`/api/students/${ids['017']}`)).send({ groupCode: 'G01', version: s17.version });
    const s16 = await current(ids['016']);
    const k = key();
    const body = { groupCode: 'G01', version: s16.version };
    const first = await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send(body);
    const retry = await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send(body);
    expect(first.status).toBe(409);
    expect(first.body.error).toBe('GROUP_FULL');
    expect(retry.status).toBe(409);
    expect(retry.body).toEqual(first.body);
    expect((await current(ids['016'])).groupCode).toBe('G03');
  });

  test('ten copies at the same instant: applied once; the others replay it or are told to retry', async () => {
    const before = await current(ids['016']);
    const k = key();
    const body = { fullName: 'Joseph Parallel Phiri', version: before.version };
    const answers = await Promise.all(
      Array.from({ length: 10 }, () => asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send(body))
    );
    for (const a of answers) {
      expect([200, 409]).toContain(a.status);
      if (a.status === 409) expect(a.body.error).toBe('CONFLICT'); // "still being processed", never VERSION_CONFLICT
    }
    expect(answers.filter((a) => a.status === 200).length).toBeGreaterThanOrEqual(1);
    expect((await current(ids['016'])).version).toBe(before.version + 1);
  });
});

describe('a key cannot be misused', () => {
  test('the same key with a different change is refused: OPERATION_ID_REUSED', async () => {
    const s = await current(ids['016']);
    const k = key();
    await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send({ fullName: 'Name One', version: s.version });
    const other = await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send({ fullName: 'Name Two', version: s.version + 1 });
    expect(other.status).toBe(422);
    expect(other.body.error).toBe('OPERATION_ID_REUSED');
    expect((await current(ids['016'])).fullName).toBe('Name One');
  });

  test("another account cannot replay someone else's answer with the same key", async () => {
    const s = await current(ids['016']);
    const k = key();
    const body = { fullName: 'Owner Change', version: s.version };
    await asLecturer(request(app).put(`/api/students/${ids['016']}`), k).send(body);
    const thief = await request(app)
      .put(`/api/students/${ids['016']}`)
      .set('Authorization', `Bearer ${otherLecturerToken}`)
      .set('Idempotency-Key', k)
      .send(body);
    expect(thief.status).toBe(422);
    expect(thief.body.error).toBe('OPERATION_ID_REUSED');
  });

  test('a key that is not a UUID is refused before anything happens', async () => {
    const res = await as('017', request(app).put('/api/students/me'), 'not-a-uuid').send({ fullName: 'Never Saved' });
    expect(res.status).toBe(422);
    expect(res.body.error).toBe('VALIDATION_ERROR');
    expect((await as('017', request(app).get('/api/students/me'))).body.student.fullName).not.toBe('Never Saved');
  });

  test('a receipt left unfinished by a crashed request is released after 2 minutes, then the change runs', async () => {
    const k = key();
    const [[acc]] = await pool.query('SELECT account_id FROM accounts WHERE student_id = ?', [ids['017']]);
    const body = { fullName: 'After A Crash' };
    // What a request that died half-way leaves behind: a claimed key, no answer.
    const { fingerprint } = require('../middleware/idempotency');
    const hash = fingerprint({ method: 'PUT', baseUrl: '/api/students', path: '/me', body });
    await pool.query(
      'INSERT INTO operation_receipts (operation_id, account_id, request_hash, created_at) VALUES (?, ?, ?, NOW(3) - INTERVAL 3 MINUTE)',
      [k, acc.account_id, hash]
    );
    const res = await as('017', request(app).put('/api/students/me'), k).send(body);
    expect(res.status).toBe(200);
    expect(res.body.student.fullName).toBe('After A Crash');
  });

  test('withdrawing a request, sent twice: the retry says CANCELLED (not REQUEST_NOT_FOUND)', async () => {
    await as('017', request(app).post('/api/group-requests')).send({ groupCode: 'G04' });
    const k = key();
    const first = await as('017', request(app).delete('/api/my-requests/group'), k);
    const retry = await as('017', request(app).delete('/api/my-requests/group'), k);
    expect(first.status).toBe(200);
    expect(retry.status).toBe(200);
    expect(retry.body.status).toBe('CANCELLED');
  });
});
