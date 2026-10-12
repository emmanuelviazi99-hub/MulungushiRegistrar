// tests/capacity.test.js
//
// The 15-seat rule (brief: Tests 9-12 and Challenge 1), on EVERY path that can
// put someone in a group:
//   - assigning an existing student           POST /api/students/:id/group
//   - creating a student straight into a group POST /api/students  { groupCode }
//   - the offline-sync CREATE operation        POST /api/students/mutate
//
// History: the last two used to count and insert without a lock. Under 10
// simultaneous requests a group at 14/15 reached 24/15. The "creating" tests
// below are the regression guard.
//
// Requires TEST_DB_NAME (see .env.example): a database that is safe to drop.

process.env.NODE_ENV = 'test';

const crypto = require('crypto');
const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, loginAs, nrcFor } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');

let lecturerToken;
const lecturer = (req) => req.set('Authorization', `Bearer ${lecturerToken}`);

// NB: names must be letters only — the API (rightly) rejects digits in a name.
async function createUnassignedStudent(studentNumber, name = 'Filler Student') {
  const res = await lecturer(request(app).post('/api/students')).send({
    fullName: name,
    studentNumber,
    nrc: nrcFor(studentNumber),
    dateOfBirth: '2003-01-01',
    programmeCode: 'CS', // no groupCode -> Unassigned
  });
  expect(res.status).toBe(201);
  return res.body.student;
}
const assign = (studentId, groupCode) => lecturer(request(app).post(`/api/students/${studentId}/group`)).send({ groupCode });
const activeIn = async (groupCode) =>
  (await pool.query(
    'SELECT COUNT(*) AS n FROM students s JOIN lab_groups g ON g.group_id = s.group_id WHERE g.group_code = ? AND s.active = 1',
    [groupCode]
  ))[0][0].n;

/** Empties a group, then puts exactly `count` active students in it, straight into the database. */
async function resetGroupTo(groupCode, count, numberPrefix) {
  const [[{ group_id: groupId }]] = await pool.query('SELECT group_id FROM lab_groups WHERE group_code = ?', [groupCode]);
  const [[{ programme_id: programmeId }]] = await pool.query("SELECT programme_id FROM programmes WHERE code = 'CS'");
  await pool.query('UPDATE students SET group_id = NULL WHERE group_id = ?', [groupId]);
  await pool.query("DELETE FROM students WHERE student_number REGEXP '^9[5-9]'");
  for (let i = 0; i < count; i += 1) {
    const number = `${numberPrefix}${String(i).padStart(6, '0')}`;
    await pool.query(
      "INSERT INTO students (student_id, student_number, nrc, date_of_birth, full_name, programme_id, group_id) VALUES (?, ?, ?, '2003-01-01', 'Seat Holder', ?, ?)",
      [crypto.randomUUID(), number, nrcFor(number), programmeId, groupId]
    );
  }
}

beforeAll(async () => {
  await resetDb();
  const l = await createLecturer(pool);
  lecturerToken = await loginAs(app, l.email, l.password);
});
afterAll(async () => {
  await pool.end();
});

// ---------------------------------------------------------------------------
describe('assigning existing students', () => {
  test('Test 9 — a group with 14 active students can accept one more', async () => {
    const student = await createUnassignedStudent('900000014', 'Fourteenth Candidate');
    const res = await assign(student.studentId, 'G01');
    expect(res.status).toBe(200);
    expect(res.body.student.groupCode).toBe('G01');
    expect(await activeIn('G01')).toBe(15);
  });

  test('Test 10 — a full group (15) rejects a new assignment with GROUP_FULL', async () => {
    const extra = await createUnassignedStudent('900000016', 'One Too Many');
    const res = await assign(extra.studentId, 'G01');
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('GROUP_FULL');
    expect(await activeIn('G01')).toBe(15);
  });

  test('Test 12 — a failed transfer leaves the student in their previous group', async () => {
    const student = await createUnassignedStudent('900000020', 'Stays Put');
    expect((await assign(student.studentId, 'G02')).status).toBe(200);

    const res = await assign(student.studentId, 'G01'); // G01 is full
    expect(res.status).toBe(409);

    const check = await lecturer(request(app).get(`/api/students/${student.studentId}`));
    expect(check.body.student.groupCode).toBe('G02'); // unchanged
  });

  test('Challenge 1 / Test 11 — exactly one of two simultaneous requests for the last place succeeds', async () => {
    const startingCount = await activeIn('G03');
    for (let i = 0; i < 14 - startingCount; i += 1) {
      const s = await createUnassignedStudent(`91${String(i).padStart(7, '0')}`);
      await assign(s.studentId, 'G03');
    }
    expect(await activeIn('G03')).toBe(14);

    const clientA = await createUnassignedStudent('920000001', 'Client Alpha');
    const clientB = await createUnassignedStudent('920000002', 'Client Bravo');
    const [resA, resB] = await Promise.all([assign(clientA.studentId, 'G03'), assign(clientB.studentId, 'G03')]);

    expect([resA.status, resB.status].sort()).toEqual([200, 409]);
    expect(await activeIn('G03')).toBe(15); // never 16
  });

  test('Challenge 1, repeated — 20 resets from a clean fixture: the group never exceeds 15', async () => {
    for (let run = 0; run < 20; run += 1) {
      await resetGroupTo('G04', 0, '93');
      const tag = String(run).padStart(2, '0');
      for (let i = 0; i < 14; i += 1) {
        const s = await createUnassignedStudent(`93${tag}${String(i).padStart(5, '0')}`);
        expect((await assign(s.studentId, 'G04')).status).toBe(200);
      }
      const lastA = await createUnassignedStudent(`94${tag}00001`, 'Last Seat Alpha');
      const lastB = await createUnassignedStudent(`94${tag}00002`, 'Last Seat Bravo');
      const [a, b] = await Promise.all([assign(lastA.studentId, 'G04'), assign(lastB.studentId, 'G04')]);

      expect([a.status, b.status].sort()).toEqual([200, 409]);
      expect(await activeIn('G04')).toBe(15);
    }
  }, 120000);
});

// ---------------------------------------------------------------------------
describe('creating a student straight into a group (regression: this path used to have no lock)', () => {
  const ROUNDS = 5;
  const RACERS = 10;

  const directCreate = (number, groupCode = 'G02') =>
    lecturer(request(app).post('/api/students')).send({
      fullName: 'Racing Candidate', studentNumber: number, nrc: nrcFor(number), dateOfBirth: '2003-01-01', programmeCode: 'CS', groupCode,
    });
  const offlineCreate = (number, groupCode = 'G02') =>
    lecturer(request(app).post('/api/students/mutate')).send({
      operationId: crypto.randomUUID(),
      type: 'CREATE',
      payload: { fullName: 'Offline Candidate', studentNumber: number, nrc: nrcFor(number), dateOfBirth: '2003-01-01', programmeCode: 'CS', groupCode },
    });

  test('creating into a group with room works, and with no room is refused with nothing half-created', async () => {
    await resetGroupTo('G02', 14, '950');
    const ok = await directCreate('960000001');
    expect(ok.status).toBe(201);
    expect(ok.body.student.groupCode).toBe('G02');

    const before = (await pool.query('SELECT COUNT(*) AS n FROM students'))[0][0].n;
    const full = await directCreate('960000002');
    expect(full.status).toBe(409);
    expect(full.body.error).toBe('GROUP_FULL');
    expect((await pool.query('SELECT COUNT(*) AS n FROM students'))[0][0].n).toBe(before); // no orphan row
    expect((await pool.query("SELECT 1 FROM students WHERE student_number = '960000002'"))[0]).toHaveLength(0);
  });

  test(`${RACERS} simultaneous POST /api/students for the last seat: exactly one wins (x${ROUNDS} rounds)`, async () => {
    for (let round = 1; round <= ROUNDS; round += 1) {
      await resetGroupTo('G02', 14, `95${round}`);
      const results = await Promise.all(Array.from({ length: RACERS }, (_, i) => directCreate(`96${round}${String(i).padStart(6, '0')}`)));
      const won = results.filter((r) => r.status === 201).length;
      const refused = results.filter((r) => r.status === 409 && r.body.error === 'GROUP_FULL').length;
      expect({ won, refused }).toEqual({ won: 1, refused: RACERS - 1 });
      expect(await activeIn('G02')).toBe(15);
    }
  }, 60000);

  test(`${RACERS} simultaneous offline-sync CREATE operations for the last seat: exactly one wins (x${ROUNDS} rounds)`, async () => {
    for (let round = 1; round <= ROUNDS; round += 1) {
      await resetGroupTo('G02', 14, `95${round}`);
      const results = await Promise.all(Array.from({ length: RACERS }, (_, i) => offlineCreate(`97${round}${String(i).padStart(6, '0')}`)));
      const won = results.filter((r) => r.status === 200).length;
      const refused = results.filter((r) => r.status === 409 && r.body.error === 'GROUP_FULL').length;
      expect({ won, refused }).toEqual({ won: 1, refused: RACERS - 1 });
      expect(await activeIn('G02')).toBe(15);
    }
  }, 60000);

  test('all three paths racing for the same last seat: exactly one wins in total', async () => {
    for (let round = 1; round <= ROUNDS; round += 1) {
      await resetGroupTo('G02', 14, `95${round}`);
      const waiting = [];
      for (let i = 0; i < 3; i += 1) waiting.push(await createUnassignedStudent(`98${round}${String(i).padStart(6, '0')}`, 'Waiting Student'));

      const attempts = [
        ...[0, 1, 2, 3].map((i) => directCreate(`96${round}${String(i).padStart(6, '0')}`)),
        ...[0, 1, 2, 3].map((i) => offlineCreate(`97${round}${String(i).padStart(6, '0')}`)),
        ...waiting.map((s) => assign(s.studentId, 'G02')),
      ];
      const results = await Promise.all(attempts);

      const won = results.filter((r) => [200, 201].includes(r.status)).length;
      const refused = results.filter((r) => r.status === 409 && r.body.error === 'GROUP_FULL').length;
      expect({ won, refused }).toEqual({ won: 1, refused: attempts.length - 1 });
      expect(await activeIn('G02')).toBe(15);
    }
  }, 90000);
});
