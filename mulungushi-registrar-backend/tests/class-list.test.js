// tests/class-list.test.js
//
// The lecturer's class list: import from a spreadsheet (preview, then save; the
// list kept as it is), download as .xlsx, add a group, and sign-up for a student
// who was imported (no NRC or birth date on file). The spreadsheet is built here
// in the same layout as the real list (headings "GROUP 1", "GROUP12", numbered
// rows, Sex column, programmes written many ways); the names are made up.

process.env.NODE_ENV = 'test';

const request = require('supertest');
const ExcelJS = require('exceljs');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { createLecturer, loginAs, signUpStudent, lastCodeFor } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');
const classList = require('../services/classlist.service');

let lecturerToken;
let studentToken;
const asLecturer = (req) => req.set('Authorization', `Bearer ${lecturerToken}`);

// 2410000NN: student numbers that are not in seed.sql
const num = (n) => `2410000${String(n).padStart(2, '0')}`;
// letters only (names can't contain digits): 1 -> "Ba", 5 -> "Fa"
const tag = (n) => 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'[n % 26] + 'abcdefghijklmnopqrstuvwxyz'[Math.floor(n / 26) % 26];

/** A class list like the lecturer's: G05 has 16, G12 is typed "GROUP12", a duplicate, an empty slot. */
async function classListFile() {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet('Sheet1');
  ws.addRow([]);
  ws.addRow([]);
  const programmes = ['BSCS', 'BCSC', 'Computer Science', 'BSc in Computer Science', 'IT', 'BSIT', 'ICT WITH EDUCATION', 'BsCS'];
  let n = 1;
  const group = (heading, size, { duplicateOf = null, blankSlot = false } = {}) => {
    ws.addRow([null, null, null, heading]);
    for (let i = 1; i <= size; i++) {
      ws.addRow([null, i, `First${tag(n)}`, `Surname${tag(n)}`, Number(num(n)), i % 2 ? 'M' : 'Female', programmes[n % programmes.length]]);
      n++;
    }
    if (duplicateOf) ws.addRow([null, size + 1, 'Again', 'Listed', Number(num(duplicateOf)), 'F', 'BSCS']);
    if (blankSlot) ws.addRow([null, size + 2]);
  };
  group('GROUP 5', 16);                        // 16: bigger than the usual 15, kept whole
  group('GROUP 6', 3, { duplicateOf: 2 });     // student 2 again (first seen in G05)
  group('GROUP12', 2, { blankSlot: true });    // heading without a space; an empty numbered row
  ws.addRow([null, 1, 'Nameless', '', 12345, 'M', 'BSCS']); // not a 9-digit number
  ws.addRow([null, 2, 'Odd', 'Course', Number(num(90)), 'M', 'Medicine']); // unknown programme
  return Buffer.from(await wb.xlsx.writeBuffer());
}

const importFile = (buffer, apply) =>
  asLecturer(request(app).post('/api/students/import')).send({
    fileName: 'Mobile application Group List.xlsx',
    fileBase64: buffer.toString('base64'),
    apply,
  });

beforeAll(async () => {
  await resetDb();
  const lecturer = await createLecturer(pool);
  lecturerToken = await loginAs(app, lecturer.email, lecturer.password);
  studentToken = (await signUpStudent(app, outbox, { studentNumber: '202301010', email: 'stud@test.example' })).body.token;
});

afterAll(async () => {
  await pool.end();
});

describe('reading the class list', () => {
  test('headings with or without a space, and programmes written many ways', () => {
    expect(classList.groupCodeFrom('GROUP 1')).toBe('G01');
    expect(classList.groupCodeFrom('GROUP12')).toBe('G12');
    expect(classList.groupCodeFrom('Group 03')).toBe('G03');
    expect(classList.groupCodeFrom('15')).toBeNull();
    for (const p of ['BSCS', 'BsCS', 'BCSC', 'CS', 'COMPUTER SCIENCE', 'BSc in Computer Science', 'Bsc Computer Science']) {
      expect(classList.programmeCodeFrom(p)).toBe('CS');
    }
    for (const p of ['IT', 'BSIT', 'Information Technology']) expect(classList.programmeCodeFrom(p)).toBe('IT');
    for (const p of ['ICT WITH EDUCATION', 'BICTED']) expect(classList.programmeCodeFrom(p)).toBe('ICTED');
    expect(classList.programmeCodeFrom('Medicine')).toBeNull();
  });
});

describe('POST /api/students/import', () => {
  test('lecturers only', async () => {
    const buffer = await classListFile();
    const asStudent = await request(app).post('/api/students/import').set('Authorization', `Bearer ${studentToken}`)
      .send({ fileName: 'x.xlsx', fileBase64: buffer.toString('base64') });
    expect(asStudent.status).toBe(403);
    const nobody = await request(app).post('/api/students/import').send({ fileName: 'x.xlsx', fileBase64: buffer.toString('base64') });
    expect(nobody.status).toBe(401);
  });

  test('the preview saves nothing and says exactly what would happen', async () => {
    const before = (await pool.query('SELECT COUNT(*) AS n FROM students'))[0][0].n;
    const res = await importFile(await classListFile(), false);
    expect(res.status).toBe(200);
    expect(res.body.applied).toBe(false);
    expect(res.body.studentsInFile).toBe(22);          // 16 + 3 + 1 duplicate + 2 (bad rows are not students)
    expect(res.body.toAdd).toBe(21);
    const byCode = Object.fromEntries(res.body.groups.map((g) => [g.groupCode, g]));
    expect(byCode.G05.willHave).toBe(16);
    expect(byCode.G05.capacity).toBe(16);               // kept whole, not cut to 15: shown as 16 of 16
    expect(byCode.G05.overUsualSize).toBe(true);
    expect(byCode.G05.exists).toBe(false);
    expect(byCode.G12.willHave).toBe(2);
    expect(res.body.programmes).toEqual(expect.objectContaining({ CS: expect.any(Number), IT: expect.any(Number), ICTED: expect.any(Number) }));
    const kinds = res.body.problems.map((p) => p.kind).sort();
    expect(kinds).toEqual(['BAD_STUDENT_NUMBER', 'DUPLICATE_IN_FILE', 'UNKNOWN_PROGRAMME']);
    expect(res.body.problems.find((p) => p.kind === 'DUPLICATE_IN_FILE').message).toMatch(/first one is kept/);
    expect(res.body.blankRowsSkipped).toBe(1);
    const after = (await pool.query('SELECT COUNT(*) AS n FROM students'))[0][0].n;
    expect(after).toBe(before);
  });

  test('saving adds everyone as listed, creates the groups, and running it again adds nobody', async () => {
    const buffer = await classListFile();
    const res = await importFile(buffer, true);
    expect(res.status).toBe(201);
    expect(res.body.added).toBe(21);

    const [[g05]] = await pool.query("SELECT capacity, (SELECT COUNT(*) FROM students s WHERE s.group_id = g.group_id AND s.active = 1) AS members FROM lab_groups g WHERE group_code = 'G05'");
    expect(g05).toEqual({ capacity: 15, members: 16 }); // everyone kept; the limit stays 15
    const totals = await asLecturer(request(app).get('/api/groups'));
    expect(totals.body.groups.find((g) => g.groupCode === 'G05')).toEqual({ groupCode: 'G05', capacity: 16, limit: 15, activeCount: 16, full: true });
    const [[dup]] = await pool.query("SELECT g.group_code FROM students s JOIN lab_groups g USING (group_id) WHERE s.student_number = ?", [num(2)]);
    expect(dup.group_code).toBe('G05');                  // the first listing counts
    const [[row]] = await pool.query('SELECT nrc, date_of_birth, full_name FROM students WHERE student_number = ?', [num(1)]);
    expect(row).toEqual({ nrc: null, date_of_birth: null, full_name: `First${tag(1)} Surname${tag(1)}` });
    const [[ict]] = await pool.query("SELECT p.code FROM students s JOIN programmes p USING (programme_id) WHERE s.student_number = ?", [num(6)]); // listed as "ICT WITH EDUCATION"
    expect(ict.code).toBe('ICTED');

    const again = await importFile(buffer, true);
    expect(again.status).toBe(201);
    expect(again.body.added).toBe(0);
    expect(again.body.alreadyOnRoster).toBe(21);
  });

  test('a lecturer\'s change in the app is kept when the sheet is imported again', async () => {
    const [[s]] = await pool.query('SELECT student_id, version FROM students WHERE student_number = ?', [num(3)]);
    const moved = await asLecturer(request(app).put(`/api/students/${s.student_id}`)).send({ groupCode: 'G12', version: s.version });
    expect(moved.status).toBe(200);
    await importFile(await classListFile(), true);
    const [[after]] = await pool.query("SELECT g.group_code FROM students s JOIN lab_groups g USING (group_id) WHERE s.student_number = ?", [num(3)]);
    expect(after.group_code).toBe('G12');
  });

  test('a group the list filled past 15 is full, takes nobody new, and reads 15/15 once members leave', async () => {
    const g05 = async () => (await asLecturer(request(app).get('/api/groups'))).body.groups.find((g) => g.groupCode === 'G05');
    const moveTo = async (number, groupCode) => {
      const [[s]] = await pool.query('SELECT student_id, version FROM students WHERE student_number = ?', [number]);
      return asLecturer(request(app).put(`/api/students/${s.student_id}`)).send({ groupCode, version: s.version });
    };
    const membersOfG05 = async () => (await pool.query(
      "SELECT s.student_number FROM students s JOIN lab_groups g USING (group_id) WHERE g.group_code = 'G05' AND s.active = 1 ORDER BY s.student_number")) [0].map((r) => r.student_number);

    // the test above moved one out of G05 (16 -> 15); a later list adds one back: 16 again
    const csv = 'Student number,Full name,Programme,Group\n' + `${num(70)},Late Comer,Computer Science,Group 5\n`;
    const added = await asLecturer(request(app).post('/api/students/import')).send({ fileName: 'late.csv', fileBase64: Buffer.from(csv).toString('base64'), apply: true });
    expect(added.body.groups[0]).toEqual(expect.objectContaining({ groupCode: 'G05', willHave: 16, capacity: 16, overUsualSize: true }));
    expect((await g05()).activeCount).toBe(16);
    expect(await g05()).toEqual(expect.objectContaining({ full: true }));
    expect((await g05()).capacity).toBe((await g05()).activeCount);          // 16/16, not 16/15 or 16/18
    expect((await moveTo(num(3), 'G05')).body.error).toBe('GROUP_FULL');      // nobody new while over

    let members = await membersOfG05();
    while (members.length > 15) {
      expect((await moveTo(members[0], 'G12')).status).toBe(200);
      members = await membersOfG05();
    }
    expect(await g05()).toEqual({ groupCode: 'G05', capacity: 15, limit: 15, activeCount: 15, full: true }); // 15/15
    expect((await moveTo(num(3), 'G05')).body.error).toBe('GROUP_FULL');

    expect((await moveTo(members[0], 'G12')).status).toBe(200);
    expect(await g05()).toEqual({ groupCode: 'G05', capacity: 15, limit: 15, activeCount: 14, full: false });
    expect((await moveTo(num(3), 'G05')).status).toBe(200);                  // a place again
  });

  test('a file that is not a spreadsheet is refused clearly', async () => {
    const res = await asLecturer(request(app).post('/api/students/import')).send({ fileName: 'list.xlsx', fileBase64: Buffer.from('not a spreadsheet').toString('base64') });
    expect(res.status).toBe(422);
    expect(res.body.error).toBe('UNREADABLE_FILE');
  });

  test('a CSV with a header row works too', async () => {
    const csv = 'Student number,Full name,Programme,Group\n' + `${num(60)},Csv Person,IT,Group 14\n${num(61)},Other Person,Computer Science,\n`;
    const res = await asLecturer(request(app).post('/api/students/import')).send({ fileName: 'list.csv', fileBase64: Buffer.from(csv).toString('base64'), apply: true });
    expect(res.status).toBe(201);
    expect(res.body.added).toBe(2);
    expect(res.body.unassigned).toBe(1);
    const [[g]] = await pool.query("SELECT g.group_code FROM students s JOIN lab_groups g USING (group_id) WHERE s.student_number = ?", [num(60)]);
    expect(g.group_code).toBe('G14');
  });
});

describe('GET /api/students/export', () => {
  test('an .xlsx with a Groups summary, All students, and a sheet per group', async () => {
    const res = await asLecturer(request(app).get('/api/students/export'))
      .buffer(true)
      .parse((r, cb) => { const chunks = []; r.on('data', (c) => chunks.push(c)); r.on('end', () => cb(null, Buffer.concat(chunks))); });
    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toMatch(/spreadsheetml/);
    expect(res.headers['content-disposition']).toMatch(/attachment; filename="Mulungushi-Registrar-groups-\d{4}-\d{2}-\d{2}\.xlsx"/);
    const wb = new ExcelJS.Workbook();
    await wb.xlsx.load(res.body);
    const names = wb.worksheets.map((w) => w.name);
    expect(names.slice(0, 2)).toEqual(['Groups', 'All students']);
    expect(names).toEqual(expect.arrayContaining(['G05', 'G06', 'G12', 'Unassigned']));
    const g05 = wb.getWorksheet('G05');
    expect(g05.getRow(1).values.slice(1)).toEqual(['No.', 'Student number', 'Full name', 'Programme', 'Group', 'Signed up']);
    expect(g05.rowCount - 1).toBe(15); // 16 imported, one moved to G12 by the lecturer in the test above
    const [[{ n }]] = await pool.query('SELECT COUNT(*) AS n FROM students WHERE active = 1');
    expect(wb.getWorksheet('All students').rowCount - 1).toBe(n);
  });

  test('lecturers only', async () => {
    const res = await request(app).get('/api/students/export').set('Authorization', `Bearer ${studentToken}`);
    expect(res.status).toBe(403);
  });
});

describe('POST /api/groups (Add group)', () => {
  test('the next number after the highest, with the usual capacity', async () => {
    const res = await asLecturer(request(app).post('/api/groups')).send({});
    expect(res.status).toBe(201);
    expect(res.body.group).toEqual({ groupCode: 'G15', capacity: 15, members: 0 });
    const totals = await asLecturer(request(app).get('/api/groups'));
    expect(totals.body.groups.map((g) => g.groupCode)).toContain('G15');
  });

  test('a capacity can be given; nonsense is refused; students cannot add groups', async () => {
    expect((await asLecturer(request(app).post('/api/groups')).send({ capacity: 20 })).body.group).toEqual({ groupCode: 'G16', capacity: 20, members: 0 });
    expect((await asLecturer(request(app).post('/api/groups')).send({ capacity: 0 })).status).toBe(422);
    expect((await request(app).post('/api/groups').set('Authorization', `Bearer ${studentToken}`).send({})).status).toBe(403);
  });
});

describe('signing up when you were imported from the class list', () => {
  const ask = (body) => request(app).post('/api/auth/register/request').send(body);
  const details = (n, extra = {}) => ({ fullName: `First${tag(n)} Surname${tag(n)}`, studentNumber: num(n), nrc: `55${String(n).padStart(4, '0')}/10/1`, dateOfBirth: '2004-05-06', email: `imported${n}@test.example`, phone: '0977123456', ...extra });

  test('the student signs up with their own NRC and birth date, which are saved only after the code', async () => {
    const d = details(5);
    expect((await ask(d)).status).toBe(200);
    let [[row]] = await pool.query('SELECT nrc FROM students WHERE student_number = ?', [num(5)]);
    expect(row.nrc).toBeNull();                         // nothing written before the code is confirmed
    const code = lastCodeFor(outbox, d.email);
    const verified = await request(app).post('/api/auth/register/verify').send({ email: d.email, code });
    expect(verified.body.fullName).toBe(`First${tag(5)} Surname${tag(5)}`); // the name from the class list
    const done = await request(app).post('/api/auth/register/complete').send({ email: d.email, password: 'Str0ngPass!' });
    expect(done.status).toBe(201);
    expect(done.body.student.groupCode).toBe('G05');    // in the group the lecturer listed
    [[row]] = await pool.query('SELECT nrc, DATE_FORMAT(date_of_birth, "%Y-%m-%d") AS dob FROM students WHERE student_number = ?', [num(5)]);
    expect(row).toEqual({ nrc: d.nrc, dob: '2004-05-06' });
  });

  test('after that, the row is matched like any other: the right details are needed, and only once', async () => {
    const again = await ask(details(5, { email: 'someone.else@test.example' }));
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('ALREADY_REGISTERED');
  });

  test('an NRC that belongs to another student is refused', async () => {
    const res = await ask(details(6, { nrc: '550005/10/1' })); // student 5's NRC
    expect(res.status).toBe(404);
    expect(res.body.error).toBe('NOT_ON_ROSTER');
  });

  test('two people asking for the same imported number: only the one who completes first gets it', async () => {
    const a = details(8, { email: 'first8@test.example' });
    const b = details(8, { email: 'second8@test.example', nrc: '558888/10/1' });
    expect((await ask(a)).status).toBe(200);
    expect((await ask(b)).status).toBe(200);
    for (const who of [a, b]) {
      const code = lastCodeFor(outbox, who.email);
      expect((await request(app).post('/api/auth/register/verify').send({ email: who.email, code })).status).toBe(200);
    }
    expect((await request(app).post('/api/auth/register/complete').send({ email: a.email, password: 'Str0ngPass!' })).status).toBe(201);
    const late = await request(app).post('/api/auth/register/complete').send({ email: b.email, password: 'Str0ngPass!' });
    expect(late.status).toBe(409);
    const [[row]] = await pool.query('SELECT nrc FROM students WHERE student_number = ?', [num(8)]);
    expect(row.nrc).toBe(a.nrc);
  });
});
