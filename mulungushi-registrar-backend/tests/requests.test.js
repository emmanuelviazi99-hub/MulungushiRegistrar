// tests/requests.test.js
//
// Change requests (group change and number correction), which now share one
// table and one implementation (services/request.service.js):
//   - nothing changes until a lecturer approves, and history is kept
//   - one open request per student per kind, enforced by the database
//   - approval re-checks the 15-seat limit and is atomic
//   - two lecturers approving at once apply the change exactly once
//   - the two kinds, and the two roles, cannot be mixed up

process.env.NODE_ENV = 'test';

const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, loginAs, signUpStudent, nrcFor } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');

let lecturerToken;
const tokens = {};
const asLecturer = (req) => req.set('Authorization', `Bearer ${lecturerToken}`);
const as = (who, req) => req.set('Authorization', `Bearer ${tokens[who]}`);

const me = async (who) => (await as(who, request(app).get('/api/students/me'))).body.student;
const askGroup = (who, groupCode, reason) => as(who, request(app).post('/api/group-requests')).send({ groupCode, reason });
const askNumber = (who, newNumber) => as(who, request(app).post('/api/number-correction-requests')).send({ newNumber });
const approveGroup = (id) => asLecturer(request(app).put(`/api/group-requests/${id}/approve`));
const queue = async (path, status = 'PENDING') =>
  (await asLecturer(request(app).get(`/api/${path}?status=${status}`))).body.requests;

beforeAll(async () => {
  await resetDb();
  const lecturer = await createLecturer(pool);
  lecturerToken = await loginAs(app, lecturer.email, lecturer.password);
  // Seeded: 202301015 is in G02, 016 in G03, 017 in G04, 018 Unassigned, G01 holds 14/15.
  for (const n of ['015', '016', '017', '018']) {
    const done = await signUpStudent(app, outbox, { studentNumber: `202301${n}`, email: `s${n}@example.com` });
    tokens[n] = done.body.token;
  }
});
afterAll(async () => {
  await pool.end();
});

describe('group change requests', () => {
  test('a request is PENDING and changes nothing; approval moves the student and keeps the history', async () => {
    const before = await me('015');
    const ask = await askGroup('015', 'G03', 'Clashes with my Thursday lecture');
    expect(ask.status).toBe(201);
    expect(ask.body.status).toBe('PENDING');
    expect((await me('015')).groupCode).toBe('G02'); // untouched until approved

    const pending = (await queue('group-requests')).find((r) => r.requestId === ask.body.requestId);
    expect(pending).toMatchObject({
      kind: 'GROUP',
      studentNumber: '202301015',
      fromGroupCode: 'G02',
      toGroupCode: 'G03',
      reason: 'Clashes with my Thursday lecture',
      resolvedAt: null,
    });

    expect((await approveGroup(ask.body.requestId)).status).toBe(200);
    const after = await me('015');
    expect(after.groupCode).toBe('G03');
    expect(after.version).toBe(before.version + 1);

    // History: the request still says where they came from, after the student row moved on.
    const done = (await queue('group-requests', 'APPROVED')).find((r) => r.requestId === ask.body.requestId);
    expect(done).toMatchObject({ status: 'APPROVED', fromGroupCode: 'G02', toGroupCode: 'G03' });
    expect(done.resolvedAt).not.toBeNull();
  });

  test('one open request per kind — enforced by the database even when five arrive at once', async () => {
    const results = await Promise.all(Array.from({ length: 5 }, () => askGroup('016', 'G04')));
    expect(results.filter((r) => r.status === 201)).toHaveLength(1);
    expect(results.filter((r) => r.status === 409).every((r) => r.body.error === 'REQUEST_ALREADY_PENDING')).toBe(true);
    const [[{ n }]] = await pool.query(
      "SELECT COUNT(*) AS n FROM change_requests r JOIN students s USING (student_id) WHERE s.student_number = '202301016' AND r.status = 'PENDING'"
    );
    expect(n).toBe(1);

    // A different kind is a separate slot.
    expect((await askNumber('016', '202301616')).status).toBe(201);

    // Once the open one is resolved, a new one may be opened.
    const open = (await queue('group-requests')).find((r) => r.studentNumber === '202301016');
    expect((await asLecturer(request(app).put(`/api/group-requests/${open.requestId}/decline`))).status).toBe(200);
    expect((await askGroup('016', 'G04')).status).toBe(201);
  });

  test('approval re-checks the 15-seat limit: a full group leaves the request PENDING and the student where they were', async () => {
    const ask = await askGroup('018', 'G01'); // G01 is at 14/15
    expect(ask.status).toBe(201);

    // Someone else takes the last seat before the lecturer gets to it.
    const filler = await asLecturer(request(app).post('/api/students')).send({
      fullName: 'Late Arrival', studentNumber: '300000501', nrc: nrcFor('300000501'),
      dateOfBirth: '2003-01-01', programmeCode: 'CS', groupCode: 'G01',
    });
    expect(filler.status).toBe(201);

    const res = await approveGroup(ask.body.requestId);
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('GROUP_FULL');
    expect((await me('018')).groupCode).toBeNull();
    expect((await queue('group-requests')).map((r) => r.requestId)).toContain(ask.body.requestId);
  });

  test('two lecturers approving the same request at the same moment apply it exactly once', async () => {
    const before = await me('017');
    const ask = await askGroup('017', 'UNASSIGNED', 'Dropping the lab this term');
    const results = await Promise.all(Array.from({ length: 5 }, () => approveGroup(ask.body.requestId)));
    expect(results.filter((r) => r.status === 200)).toHaveLength(1);
    expect(results.filter((r) => r.status === 404).every((r) => r.body.error === 'REQUEST_NOT_FOUND')).toBe(true);
    const after = await me('017');
    expect(after.groupCode).toBeNull();
    expect(after.version).toBe(before.version + 1); // moved once, not five times
  });
});

describe('the two kinds and the two roles stay separate', () => {
  test('a group request id cannot be approved through the number-correction endpoint, and vice versa', async () => {
    const group = (await queue('group-requests')).find((r) => r.studentNumber === '202301016');
    const number = (await queue('number-correction-requests')).find((r) => r.studentNumber === '202301016');
    expect(number).toMatchObject({ kind: 'NUMBER', oldNumber: '202301016', newNumber: '202301616' });
    expect((await asLecturer(request(app).put(`/api/number-correction-requests/${group.requestId}/approve`))).status).toBe(404);
    expect((await asLecturer(request(app).put(`/api/group-requests/${number.requestId}/decline`))).status).toBe(404);
  });

  test('students cannot approve, decline or read the queue; lecturers cannot open requests', async () => {
    const group = (await queue('group-requests')).find((r) => r.studentNumber === '202301016');
    expect((await as('016', request(app).put(`/api/group-requests/${group.requestId}/approve`))).status).toBe(403);
    expect((await as('016', request(app).put(`/api/group-requests/${group.requestId}/decline`))).status).toBe(403);
    expect((await as('016', request(app).get('/api/group-requests'))).status).toBe(403);
    expect((await asLecturer(request(app).post('/api/group-requests')).send({ groupCode: 'G02' })).status).toBe(403);
  });

  test('a declined request can no longer be approved', async () => {
    const group = (await queue('group-requests')).find((r) => r.studentNumber === '202301016');
    expect((await asLecturer(request(app).put(`/api/group-requests/${group.requestId}/decline`))).status).toBe(200);
    expect((await approveGroup(group.requestId)).status).toBe(404);
  });

  test('unknown groups and badly formed numbers are refused when asking', async () => {
    const bad = await askGroup('015', 'G99');
    expect(bad.status).toBe(400);
    expect(bad.body.error).toBe('INVALID_GROUP');
    const short = await askNumber('015', '12345');
    expect(short.status).toBe(422);
    expect(short.body.error).toBe('VALIDATION_ERROR');
  });
});

describe('number corrections', () => {
  test('approving a number that someone else already has is refused, and the request stays PENDING', async () => {
    const ask = await askNumber('015', '202301001'); // Chanda Mwansa's number
    expect(ask.status).toBe(201);
    const res = await asLecturer(request(app).put(`/api/number-correction-requests/${ask.body.requestId}/approve`));
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('DUPLICATE_STUDENT_NUMBER');
    expect((await me('015')).studentNumber).toBe('202301015');
    expect((await queue('number-correction-requests')).map((r) => r.requestId)).toContain(ask.body.requestId);
  });

  test('a pending request for a student who was then deleted cannot be approved', async () => {
    const number = (await queue('number-correction-requests')).find((r) => r.studentNumber === '202301016');
    expect((await asLecturer(request(app).delete(`/api/students/${number.studentId}`))).status).toBe(200);
    const res = await asLecturer(request(app).put(`/api/number-correction-requests/${number.requestId}/approve`));
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('STUDENT_NOT_FOUND');
    const [[row]] = await pool.query('SELECT status FROM change_requests WHERE request_id = ?', [number.requestId]);
    expect(row.status).toBe('PENDING'); // the rollback left it untouched
  });
});
