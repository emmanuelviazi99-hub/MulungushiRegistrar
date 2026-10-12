// tests/app-screens.test.js
//
// The endpoints the Android screens use now that the roster, groups and requests
// live on the server instead of in the phone's memory:
//   - the lecturer's Save on the roster sheet (PUT /students/:id with number + group)
//   - a student's own requests: see the latest, withdraw an open one
//   - the lecturer's Answered history (status=ANSWERED)
//   - a student's own group and its members (GET /groups/mine)
// and the bug that started this: a group the lecturer assigns is what the
// student sees when they sign in again.

process.env.NODE_ENV = 'test';

const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, loginAs, signUpStudent } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');

let lecturerToken;
const tokens = {};
const ids = {};
const asLecturer = (req) => req.set('Authorization', `Bearer ${lecturerToken}`);
const as = (who, req) => req.set('Authorization', `Bearer ${tokens[who]}`);
const save = (studentId, body) => asLecturer(request(app).put(`/api/students/${studentId}`)).send(body);
const me = async (who) => (await as(who, request(app).get('/api/students/me'))).body.student;
const mine = async (who) => (await as(who, request(app).get('/api/my-requests'))).body;

beforeAll(async () => {
  await resetDb();
  const lecturer = await createLecturer(pool, { employeeId: 'EMP-9501' });
  lecturerToken = await loginAs(app, lecturer.email, lecturer.password);
  // Seeded: 202301015 is in G02, 016 in G03, 017 in G04, 018 Unassigned, G01 holds 14/15.
  for (const n of ['015', '016', '017', '018']) {
    const done = await signUpStudent(app, outbox, { studentNumber: `202301${n}`, email: `s${n}@test.example` });
    tokens[n] = done.body.token;
    ids[n] = done.body.student.studentId;
  }
});

afterAll(async () => {
  await pool.end();
});

describe('the lecturer assigns a group and the student sees it', () => {
  test('assigned on the roster sheet, still there after the student signs in again', async () => {
    expect((await me('018')).groupCode).toBeNull();

    const saved = await save(ids['018'], { groupCode: 'G03' });
    expect(saved.status).toBe(200);
    expect(saved.body.student.groupCode).toBe('G03');

    // a fresh sign-in reads the server's record
    const again = await request(app).post('/api/auth/login').send({ loginKey: '202301018', password: 'Str0ngPass!' });
    expect(again.status).toBe(200);
    expect(again.body.student.groupCode).toBe('G03');

    const group = await as('018', request(app).get('/api/groups/mine'));
    expect(group.status).toBe(200);
    expect(group.body.groupCode).toBe('G03');
    expect(group.body.members.map((m) => m.studentNumber)).toContain('202301018');
    // names and numbers only: no NRC, no birth date
    expect(Object.keys(group.body.members[0]).sort()).toEqual(['fullName', 'studentNumber']);
  });
});

describe('PUT /students/:id — the lecturer saves the whole sheet in one go', () => {
  test('name, programme, number and group change together', async () => {
    const before = await me('017');
    const res = await save(ids['017'], {
      fullName: 'Renamed Student',
      programmeCode: 'DS',
      studentNumber: '202388017',
      groupCode: 'UNASSIGNED',
      version: before.version,
    });
    expect(res.status).toBe(200);
    expect(res.body.student).toMatchObject({
      fullName: 'Renamed Student',
      programmeCode: 'DS',
      studentNumber: '202388017',
      groupCode: null,
    });
    // the student signs in with the corrected number from now on
    const login = await request(app).post('/api/auth/login').send({ loginKey: '202388017', password: 'Str0ngPass!' });
    expect(login.status).toBe(200);
  });

  test('a full group refuses the WHOLE save: the name is not changed either', async () => {
    // G01 is seeded with 14 of 15: 016 takes the last seat
    expect((await save(ids['016'], { groupCode: 'G01' })).status).toBe(200);

    const before = await me('015');
    const res = await save(ids['015'], { fullName: 'Should Not Stick', groupCode: 'G01' });
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('GROUP_FULL');
    const after = await me('015');
    expect(after.fullName).toBe(before.fullName);
    expect(after.groupCode).toBe('G02');
    expect(after.version).toBe(before.version);
  });

  test('a number someone else has is refused, and nothing else changes', async () => {
    const before = await me('015');
    const res = await save(ids['015'], { fullName: 'Another Name', studentNumber: '202301001' });
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('DUPLICATE_STUDENT_NUMBER');
    const after = await me('015');
    expect(after.fullName).toBe(before.fullName);
    expect(after.studentNumber).toBe('202301015');
  });

  test('an out-of-date version is refused (someone else saved first)', async () => {
    const before = await me('015');
    await save(ids['015'], { programmeCode: 'IT', version: before.version });
    const stale = await save(ids['015'], { programmeCode: 'CS', version: before.version });
    expect(stale.status).toBe(409);
    expect(stale.body.error).toBe('VERSION_CONFLICT');
  });

  test('students cannot use it, and a student\'s own PUT /students/me still cannot change number or group', async () => {
    const res = await as('015', request(app).put(`/api/students/${ids['015']}`)).send({ groupCode: 'G04' });
    expect(res.status).toBe(403);
    const own = await as('015', request(app).put('/api/students/me')).send({
      fullName: 'Own Name', studentNumber: '202377015', groupCode: 'G04',
    });
    expect(own.status).toBe(200);
    expect(own.body.student).toMatchObject({ fullName: 'Own Name', studentNumber: '202301015', groupCode: 'G02' });
  });

  test('doing exactly what an open request asked for answers that request as approved', async () => {
    const asked = await as('015', request(app).post('/api/group-requests')).send({ groupCode: 'G04' });
    expect(asked.status).toBe(201);
    const askedNumber = await as('015', request(app).post('/api/number-correction-requests')).send({ newNumber: '202366015' });
    expect(askedNumber.status).toBe(201);

    const res = await save(ids['015'], { groupCode: 'G04', studentNumber: '202366015' });
    expect(res.status).toBe(200);

    const latest = await mine('015');
    expect(latest.group).toMatchObject({ requestId: asked.body.requestId, status: 'APPROVED', toGroupCode: 'G04' });
    expect(latest.number).toMatchObject({ requestId: askedNumber.body.requestId, status: 'APPROVED', newNumber: '202366015' });
    expect(latest.group.resolvedAt).not.toBeNull();
  });

  test('a different change leaves the open request pending', async () => {
    const asked = await as('015', request(app).post('/api/group-requests')).send({ groupCode: 'G03' });
    expect(asked.status).toBe(201);
    await save(ids['015'], { groupCode: 'UNASSIGNED' });
    expect((await mine('015')).group).toMatchObject({ requestId: asked.body.requestId, status: 'PENDING' });
    await as('015', request(app).delete('/api/my-requests/group'));
  });
});

describe("a student's own requests", () => {
  test('GET /my-requests shows the latest of each kind, or null', async () => {
    const empty = await mine('018');
    expect(empty).toMatchObject({ success: true, group: null, number: null });

    await as('018', request(app).post('/api/group-requests')).send({ groupCode: 'G02' });
    const latest = await mine('018');
    expect(latest.group).toMatchObject({ status: 'PENDING', fromGroupCode: 'G03', toGroupCode: 'G02' });
    expect(latest.number).toBeNull();
  });

  test('withdrawing an open request removes it and frees the slot for a new one', async () => {
    const cancelled = await as('018', request(app).delete('/api/my-requests/group'));
    expect(cancelled.status).toBe(200);
    expect((await mine('018')).group).toBeNull();

    // the lecturer's queue no longer shows it
    const queue = (await asLecturer(request(app).get('/api/group-requests?status=PENDING'))).body.requests;
    expect(queue.find((r) => r.studentNumber === '202301018')).toBeUndefined();

    const again = await as('018', request(app).post('/api/group-requests')).send({ groupCode: 'G04' });
    expect(again.status).toBe(201);
  });

  test('nothing to withdraw: 404; an answered request can never be withdrawn', async () => {
    const none = await as('018', request(app).delete('/api/my-requests/number'));
    expect(none.status).toBe(404);
    expect(none.body.error).toBe('REQUEST_NOT_FOUND');

    const open = (await mine('018')).group;
    await asLecturer(request(app).put(`/api/group-requests/${open.requestId}/decline`));
    const late = await as('018', request(app).delete('/api/my-requests/group'));
    expect(late.status).toBe(404);
    expect((await mine('018')).group).toMatchObject({ requestId: open.requestId, status: 'DECLINED' });
  });

  test('only students, only their own, and only the two kinds', async () => {
    expect((await asLecturer(request(app).get('/api/my-requests'))).status).toBe(403);
    expect((await as('018', request(app).delete('/api/my-requests/everything'))).status).toBe(404);
    expect((await request(app).get('/api/my-requests')).status).toBe(401);
  });
});

describe('the lecturer\'s Answered history', () => {
  test('status=ANSWERED lists approved and declined requests, newest first, never pending ones', async () => {
    const answered = (await asLecturer(request(app).get('/api/group-requests?status=ANSWERED'))).body.requests;
    expect(answered.length).toBeGreaterThanOrEqual(2);
    expect(answered.every((r) => r.status === 'APPROVED' || r.status === 'DECLINED')).toBe(true);
    const times = answered.map((r) => new Date(r.resolvedAt).getTime());
    expect([...times].sort((a, b) => b - a)).toEqual(times);

    const numbers = (await asLecturer(request(app).get('/api/number-correction-requests?status=ANSWERED'))).body.requests;
    expect(numbers.every((r) => r.kind === 'NUMBER' && r.status !== 'PENDING')).toBe(true);
  });
});

describe('GET /groups/mine', () => {
  test('an unassigned student gets no group and no members; lecturers cannot use it', async () => {
    const res = await as('017', request(app).get('/api/groups/mine'));
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ groupCode: null, members: [] });
    expect((await asLecturer(request(app).get('/api/groups/mine'))).status).toBe(403);
  });
});
