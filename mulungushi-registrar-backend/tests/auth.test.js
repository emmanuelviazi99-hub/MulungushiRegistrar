// tests/auth.test.js
//
// Account creation (students and lecturers), verification codes, password
// reset and login. Codes are read from the delivery service's test outbox —
// they are never in an HTTP response.

process.env.NODE_ENV = 'test';

const crypto = require('crypto');
const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { identityOf, createLecturer, loginAs, lastCodeFor, signUpStudent } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const delivery = require('../services/delivery.service');
const { outbox } = delivery;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function studentRequestBody(studentNumber, email, overrides = {}) {
  const { nrc, dateOfBirth } = identityOf(studentNumber);
  return { fullName: 'Test Student', studentNumber, nrc, dateOfBirth, email, phone: '0977123456', ...overrides };
}
const askForCode = (body) => request(app).post('/api/auth/register/request').send(body);
const verifyCode = (email, code) => request(app).post('/api/auth/register/verify').send({ email, code });
const complete = (email, extra = {}) => request(app).post('/api/auth/register/complete').send({ email, password: 'Str0ngPass!', ...extra });

beforeAll(async () => {
  await resetDb();
});
beforeEach(() => {
  outbox.length = 0;
});
afterAll(async () => {
  await pool.end();
});

// ---------------------------------------------------------------------------
describe('student sign-up', () => {
  test('the whole flow works: request -> verify -> complete -> sign in', async () => {
    const asked = await askForCode(studentRequestBody('202301001', 'Chanda@Example.com'));
    expect(asked.status).toBe(200);
    expect(asked.body).toMatchObject({ status: 'OK', maskedEmail: 'c***@example.com', expiresInSeconds: 600, resendAfterSeconds: 30 });

    // A code was "sent" to the (lower-cased) email, and the HTTP response did not contain it.
    expect(outbox).toHaveLength(1);
    const code = outbox[0].code;
    expect(code).toMatch(/^MU-[A-Z0-9]{6}$/);
    expect(JSON.stringify(asked.body)).not.toContain(code);
    expect(outbox[0].phone).toBe('+260977123456'); // normalised

    const verified = await verifyCode('chanda@example.com', code.toLowerCase()); // case-insensitive
    expect(verified.status).toBe(200);
    expect(verified.body.fullName).toBe('Chanda Mwansa'); // the roster's name, not what was typed

    const done = await complete('chanda@example.com', { programmeCode: 'it' });
    expect(done.status).toBe(201);
    expect(done.body.token).toBeDefined();
    expect(done.body.account).toMatchObject({ role: 'STUDENT', email: 'chanda@example.com', phone: '+260977123456', displayName: 'Chanda Mwansa' });
    expect(done.body.student).toMatchObject({ studentNumber: '202301001', nrc: '700001/10/1', dateOfBirth: '2003-04-12', programmeCode: 'IT' });
    expect(JSON.stringify(done.body)).not.toMatch(/password|hash/i);

    // Sign in with the student number, and with the email in any letter case.
    expect((await request(app).post('/api/auth/login').send({ loginKey: '202301001', password: 'Str0ngPass!' })).status).toBe(200);
    expect((await request(app).post('/api/auth/login').send({ loginKey: 'CHANDA@example.COM', password: 'Str0ngPass!' })).status).toBe(200);
  });

  test('details that do not match the roster are refused with one identical answer', async () => {
    const wrongNrc = await askForCode(studentRequestBody('202301002', 'a@example.com', { nrc: '700099/10/1' }));
    const wrongDob = await askForCode(studentRequestBody('202301002', 'a@example.com', { dateOfBirth: '1999-01-01' }));
    const unknownNumber = await askForCode(studentRequestBody('202301002', 'a@example.com', { studentNumber: '999999999' }));
    const deleted = await askForCode({ ...studentRequestBody('202301002', 'a@example.com'), studentNumber: '202301099', nrc: '700099/10/1', dateOfBirth: '2001-01-01' });

    for (const res of [wrongNrc, wrongDob, unknownNumber, deleted]) {
      expect(res.status).toBe(404);
      expect(res.body.error).toBe('NOT_ON_ROSTER');
    }
    // Same body whichever part was wrong: the endpoint cannot be used to find out which student numbers exist.
    expect(wrongNrc.body).toEqual(unknownNumber.body);
    expect(wrongDob.body).toEqual(unknownNumber.body);
    expect(outbox).toHaveLength(0); // nothing was sent to anyone
  });

  test('an already-registered student, or an email already in use, is refused', async () => {
    await signUpStudent(app, outbox, { studentNumber: '202301003', email: 'mutinta@example.com' });

    const again = await askForCode(studentRequestBody('202301003', 'other@example.com'));
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('ALREADY_REGISTERED');

    const emailTaken = await askForCode(studentRequestBody('202301004', 'MUTINTA@example.com'));
    expect(emailTaken.status).toBe(409);
    expect(emailTaken.body.error).toBe('EMAIL_IN_USE');
  });

  test.each([
    ['a bad email', { email: 'not-an-email' }, 'email'],
    ['a bad phone number', { phone: '12345' }, 'phone'],
    ['a malformed NRC', { nrc: '12345A/10/1' }, 'nrc'],
    ['a date of birth in the future', { dateOfBirth: '2999-01-01' }, 'dateOfBirth'],
    ['an impossible date of birth', { dateOfBirth: '2003-02-30' }, 'dateOfBirth'],
    ['a name containing digits', { fullName: 'Test 123' }, 'fullName'],
    ['a short student number', { studentNumber: '12345' }, 'studentNumber'],
  ])('%s is rejected with 422 naming the field', async (_label, override, field) => {
    const res = await askForCode(studentRequestBody('202301005', 'v@example.com', override));
    expect(res.status).toBe(422);
    expect(res.body.error).toBe('VALIDATION_ERROR');
    expect(res.body.message.startsWith(`${field}:`)).toBe(true);
  });

  test('an NRC typed without slashes is accepted and matched', async () => {
    const res = await askForCode(studentRequestBody('202301005', 'nrc@example.com', { nrc: '700005101' }));
    expect(res.status).toBe(200);
  });

  test('passwords must be 8-72 characters', async () => {
    await askForCode(studentRequestBody('202301006', 'pw@example.com'));
    await verifyCode('pw@example.com', lastCodeFor(outbox, 'pw@example.com'));
    expect((await complete('pw@example.com', { password: 'short' })).status).toBe(422);
    expect((await complete('pw@example.com', { password: 'x'.repeat(73) })).status).toBe(422);
    expect((await complete('pw@example.com', { password: 'x'.repeat(72) })).status).toBe(201);
  });
});

// ---------------------------------------------------------------------------
describe('verification code rules', () => {
  test('5 tries: wrong guesses count down, then even the RIGHT code is refused', async () => {
    const email = 'tries@example.com';
    await askForCode(studentRequestBody('202301007', email));
    const right = lastCodeFor(outbox, email);

    for (const left of [4, 3, 2, 1]) {
      const res = await verifyCode(email, 'MU-AAAAAA');
      expect(res.status).toBe(400);
      expect(res.body).toMatchObject({ error: 'BAD_CODE', triesLeft: left });
    }
    const fifth = await verifyCode(email, 'MU-AAAAAA');
    expect(fifth.status).toBe(429);
    expect(fifth.body.error).toBe('TOO_MANY_TRIES');

    const rightTooLate = await verifyCode(email, right);
    expect(rightTooLate.status).toBe(429);
    expect(rightTooLate.body.error).toBe('TOO_MANY_TRIES');
    expect((await complete(email)).status).toBe(403); // and nothing was created
  });

  test('the code is accepted however it is typed: lower case, spaces, with or without "MU-"', async () => {
    const email = 'typed@example.com';
    await askForCode(studentRequestBody('202301007', email)); // 007's earlier code was used up above
    const code = lastCodeFor(outbox, email); // e.g. MU-7K4QXP
    const body = code.slice(3);
    const sloppy = ` ${body.slice(0, 3).toLowerCase()} ${body.slice(3).toLowerCase()} `; // " 7k4 qxp "
    const res = await verifyCode(email, sloppy);
    expect(res.status).toBe(200);
    expect((await verifyCode(email, 'MU-0O1I00')).status).toBe(422); // 0, O, 1 and I are never used in codes
  });

  test('a flood of PARALLEL guesses still gets only 5 tries', async () => {
    const email = 'flood@example.com';
    await askForCode(studentRequestBody('202301008', email));
    const right = lastCodeFor(outbox, email);

    const results = await Promise.all(Array.from({ length: 25 }, () => verifyCode(email, 'MU-BBBBBB')));
    const compared = results.filter((r) => r.body.error === 'BAD_CODE' || (r.body.error === 'TOO_MANY_TRIES' && r.body.triesLeft === 0)).length;
    expect(compared).toBeGreaterThanOrEqual(5); // 5 real comparisons happened
    const [[row]] = await pool.query("SELECT attempts FROM verification_codes WHERE email = ? ORDER BY code_id DESC LIMIT 1", [email]);
    expect(row.attempts).toBe(5); // never 6+: the limit cannot be raced
    expect((await verifyCode(email, right)).status).toBe(429);
  });

  test('a code expires after 10 minutes', async () => {
    const email = 'expiry@example.com';
    await askForCode(studentRequestBody('202301009', email));
    const code = lastCodeFor(outbox, email);
    await pool.query('UPDATE verification_codes SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE email = ?', [email]);
    const res = await verifyCode(email, code);
    expect(res.status).toBe(410);
    expect(res.body.error).toBe('CODE_EXPIRED');
  });

  test('"never asked for a code" looks exactly like "expired"', async () => {
    const res = await verifyCode('nobody@example.com', 'MU-ABCDEF');
    expect(res.status).toBe(410);
    expect(res.body.error).toBe('CODE_EXPIRED');
  });

  test('asking again within 30 seconds is refused; later it works and the OLD code dies', async () => {
    const email = 'resend@example.com';
    await askForCode(studentRequestBody('202301010', email));
    const first = lastCodeFor(outbox, email);

    const tooSoon = await askForCode(studentRequestBody('202301010', email));
    expect(tooSoon.status).toBe(429);
    expect(tooSoon.body.error).toBe('RESEND_TOO_SOON');
    expect(tooSoon.body.retryAfterSeconds).toBeGreaterThan(0);
    expect(tooSoon.body.retryAfterSeconds).toBeLessThanOrEqual(30);
    expect(outbox).toHaveLength(1); // no second message was sent

    await pool.query('UPDATE verification_codes SET created_at = DATE_SUB(created_at, INTERVAL 31 SECOND) WHERE email = ?', [email]);
    expect((await askForCode(studentRequestBody('202301010', email))).status).toBe(200);
    const second = lastCodeFor(outbox, email);
    expect(second).not.toBe(first);

    expect((await verifyCode(email, first)).status).toBe(400); // the old code no longer works
    expect((await verifyCode(email, second)).status).toBe(200);
  });

  test('a code only works for the email it was sent to, and only for its own purpose', async () => {
    await askForCode(studentRequestBody('202301011', 'owner@example.com'));
    const code = lastCodeFor(outbox, 'owner@example.com');
    expect((await verifyCode('thief@example.com', code)).status).toBe(410); // other email
    const asStaff = await request(app).post('/api/auth/staff/verify').send({ email: 'owner@example.com', code });
    expect(asStaff.status).toBe(410); // a STUDENT code cannot unlock lecturer sign-up
  });

  test('sign-up cannot be completed without verifying, and a verified code is single-use', async () => {
    const email = 'single@example.com';
    await askForCode(studentRequestBody('202301012', email));
    expect((await complete(email)).body.error).toBe('NOT_VERIFIED');

    await verifyCode(email, lastCodeFor(outbox, email));
    // Two simultaneous "complete" calls: exactly one creates the account.
    const [a, b] = await Promise.all([complete(email), complete(email)]);
    expect([a.status, b.status].sort()).toEqual([201, 403]);
    const [[{ n }]] = await pool.query('SELECT COUNT(*) AS n FROM accounts WHERE email = ?', [email]);
    expect(n).toBe(1);
    expect((await complete(email)).status).toBe(403); // and not a third time either
  });

  test('after a code is accepted there are 30 minutes to finish, not longer', async () => {
    const email = 'window@example.com';
    await askForCode(studentRequestBody('202301013', email));
    await verifyCode(email, lastCodeFor(outbox, email));
    await pool.query('UPDATE verification_codes SET verified_at = DATE_SUB(NOW(3), INTERVAL 31 MINUTE) WHERE email = ?', [email]);
    expect((await complete(email)).body.error).toBe('NOT_VERIFIED');
  });

  test('the code is stored only as a keyed hash, never in plain text', async () => {
    const email = 'hash@example.com';
    await askForCode(studentRequestBody('202301014', email));
    const code = lastCodeFor(outbox, email);
    const [[row]] = await pool.query('SELECT * FROM verification_codes WHERE email = ?', [email]);
    expect(row.code_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(JSON.stringify(row)).not.toContain(code);
    // Not a plain SHA-256 of the code either (that could be cracked offline).
    expect(row.code_hash).not.toBe(crypto.createHash('sha256').update(code).digest('hex'));
  });

  test('if delivery fails the person is told, and no dangling code is left behind', async () => {
    const spy = jest.spyOn(delivery, 'sendCode').mockRejectedValueOnce(new Error('SMTP down'));
    const email = 'fail@example.com';
    const res = await askForCode(studentRequestBody('202301015', email));
    spy.mockRestore();
    expect(res.status).toBe(502);
    expect(res.body.error).toBe('DELIVERY_FAILED');
    expect(res.body.message).not.toMatch(/SMTP/); // internals are not leaked
    const [rows] = await pool.query('SELECT 1 FROM verification_codes WHERE email = ?', [email]);
    expect(rows).toHaveLength(0);
    // ...and because it was discarded they can try again straight away.
    expect((await askForCode(studentRequestBody('202301015', email))).status).toBe(200);
  });
});

// ---------------------------------------------------------------------------
describe('lecturer sign-up', () => {
  const staffAsk = (body) => request(app).post('/api/auth/staff/request').send(body);

  test('email + employee ID must be on the staff list', async () => {
    const stranger = await staffAsk({ fullName: 'Some Person', email: 'stranger@example.com', employeeId: 'EMP-5555' });
    const wrongId = await staffAsk({ fullName: 'Mutale Chilufya', email: 'm.chilufya@mulungushi.edu.zm', employeeId: 'EMP-1001' });
    const wrongEmail = await staffAsk({ fullName: 'Mutale Chilufya', email: 'someone.else@mulungushi.edu.zm', employeeId: 'EMP-1002' });
    for (const res of [stranger, wrongId, wrongEmail]) {
      expect(res.status).toBe(404);
      expect(res.body.error).toBe('NOT_ON_ROSTER');
    }
    expect(outbox).toHaveLength(0);
  });

  test('an approved lecturer can sign up, and then can use lecturer endpoints', async () => {
    const asked = await staffAsk({ fullName: 'Mutale Chilufya', email: 'M.Chilufya@mulungushi.edu.zm', employeeId: 'emp-1002' });
    expect(asked.status).toBe(200);
    expect(outbox[0].to).toBe('m.chilufya@mulungushi.edu.zm'); // goes to the address on the staff list
    const code = outbox[0].code;

    const verified = await request(app).post('/api/auth/staff/verify').send({ email: 'm.chilufya@mulungushi.edu.zm', code });
    expect(verified.status).toBe(200);
    expect(verified.body.fullName).toBe('Mutale Chilufya');

    const done = await request(app).post('/api/auth/staff/complete').send({ email: 'm.chilufya@mulungushi.edu.zm', password: 'Lecturer#Pass1' });
    expect(done.status).toBe(201);
    expect(done.body.account).toMatchObject({ role: 'LECTURER', employeeId: 'EMP-1002', displayName: 'Mutale Chilufya', studentId: null });

    const list = await request(app).get('/api/students').set('Authorization', `Bearer ${done.body.token}`);
    expect(list.status).toBe(200);

    const again = await staffAsk({ fullName: 'Mutale Chilufya', email: 'm.chilufya@mulungushi.edu.zm', employeeId: 'EMP-1002' });
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('ALREADY_REGISTERED');
  });

  test('knowing a student\'s details gives no way to create a lecturer account', async () => {
    const res = await staffAsk({ fullName: 'Chanda Mwansa', email: 'chanda@example.com', employeeId: '202301001' });
    expect(res.status).toBe(404);
  });
});

// ---------------------------------------------------------------------------
describe('forgotten password', () => {
  const resetAsk = (email) => request(app).post('/api/auth/password/request').send({ email });

  test('the answer is identical for a real and an unknown email, and only the real one is sent a code', async () => {
    await signUpStudent(app, outbox, { studentNumber: '202301016', email: 'felix@example.com' });
    outbox.length = 0;

    const real = await resetAsk('felix@example.com');
    const unknown = await resetAsk('ghost@example.com');
    expect(real.status).toBe(200);
    expect(unknown.status).toBe(200);
    expect(Object.keys(real.body).sort()).toEqual(Object.keys(unknown.body).sort());
    expect(outbox).toHaveLength(1);
    expect(outbox[0].to).toBe('felix@example.com');

    // Asking again straight away does not reveal the cooldown either.
    const repeat = await resetAsk('felix@example.com');
    expect(repeat.status).toBe(200);
    expect(outbox).toHaveLength(1);
  });

  test('reset changes the password, and kills tokens issued before it', async () => {
    await signUpStudent(app, outbox, { studentNumber: '202301017', email: 'rabecca@example.com', password: 'OldPassw0rd!' });
    const oldToken = await loginAs(app, 'rabecca@example.com', 'OldPassw0rd!');
    expect((await request(app).get('/api/students/me').set('Authorization', `Bearer ${oldToken}`)).status).toBe(200);

    await sleep(1100); // a JWT's issue time is whole seconds; make sure the reset is strictly later
    outbox.length = 0;
    await resetAsk('rabecca@example.com');
    const code = lastCodeFor(outbox, 'rabecca@example.com');

    expect((await request(app).post('/api/auth/password/verify').send({ email: 'rabecca@example.com', code })).status).toBe(200);
    const reset = await request(app).post('/api/auth/password/reset').send({ email: 'rabecca@example.com', code, newPassword: 'NewPassw0rd!' });
    expect(reset.status).toBe(200);

    expect((await request(app).post('/api/auth/login').send({ loginKey: 'rabecca@example.com', password: 'OldPassw0rd!' })).status).toBe(401);
    const newToken = await loginAs(app, '202301017', 'NewPassw0rd!');
    expect((await request(app).get('/api/students/me').set('Authorization', `Bearer ${newToken}`)).status).toBe(200);

    const stolen = await request(app).get('/api/students/me').set('Authorization', `Bearer ${oldToken}`);
    expect(stolen.status).toBe(401); // the old session is dead
    // The code was single-use.
    const replay = await request(app).post('/api/auth/password/reset').send({ email: 'rabecca@example.com', code, newPassword: 'Another1!x' });
    expect(replay.status).toBe(410);
  });

  test('reset codes get the same 5-try limit', async () => {
    await signUpStudent(app, outbox, { studentNumber: '202301018', email: 'innocent@example.com' });
    outbox.length = 0;
    await resetAsk('innocent@example.com');
    const code = lastCodeFor(outbox, 'innocent@example.com');
    for (let i = 0; i < 5; i += 1) {
      await request(app).post('/api/auth/password/reset').send({ email: 'innocent@example.com', code: 'MU-ZZZZZZ', newPassword: 'Whatever123' });
    }
    const late = await request(app).post('/api/auth/password/reset').send({ email: 'innocent@example.com', code, newPassword: 'Whatever123' });
    expect(late.status).toBe(429);
    expect(late.body.error).toBe('TOO_MANY_TRIES');
  });
});

// ---------------------------------------------------------------------------
describe('login', () => {
  test('5 wrong passwords lock the account, even for the correct password, until the lock ends', async () => {
    await createLecturer(pool, { email: 'locky@example.com', employeeId: 'EMP-9100', password: 'RightPass#1' });
    const attempt = (password) => request(app).post('/api/auth/login').send({ loginKey: 'locky@example.com', password });

    for (let i = 0; i < 5; i += 1) expect((await attempt('wrong-password')).status).toBe(401);
    expect((await attempt('RightPass#1')).status).toBe(401); // locked: the right password does not help

    await pool.query("UPDATE accounts SET locked_until = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE email = 'locky@example.com'");
    expect((await attempt('RightPass#1')).status).toBe(200);
    const [[row]] = await pool.query("SELECT failed_logins, locked_until FROM accounts WHERE email = 'locky@example.com'");
    expect(row.failed_logins).toBe(0);
    expect(row.locked_until).toBeNull();
  });

  test('wrong-password-then-success resets the counter (so slow typos never lock you out)', async () => {
    await createLecturer(pool, { email: 'typo@example.com', employeeId: 'EMP-9101', password: 'RightPass#1' });
    const attempt = (password) => request(app).post('/api/auth/login').send({ loginKey: 'typo@example.com', password });
    for (let round = 0; round < 3; round += 1) {
      for (let i = 0; i < 4; i += 1) await attempt('nope');
      expect((await attempt('RightPass#1')).status).toBe(200);
    }
  });

  test('unknown user, wrong password and locked account all get the same answer', async () => {
    await createLecturer(pool, { email: 'same@example.com', employeeId: 'EMP-9102', password: 'RightPass#1' });
    const unknown = await request(app).post('/api/auth/login').send({ loginKey: 'nobody@example.com', password: 'whatever123' });
    const wrong = await request(app).post('/api/auth/login').send({ loginKey: 'same@example.com', password: 'whatever123' });
    const badKey = await request(app).post('/api/auth/login').send({ loginKey: 'not a login key', password: 'whatever123' });
    const unknownNumber = await request(app).post('/api/auth/login').send({ loginKey: '999999999', password: 'whatever123' });
    for (const res of [unknown, wrong, badKey, unknownNumber]) {
      expect(res.status).toBe(401);
      expect(res.body.error).toBe('INVALID_CREDENTIALS');
      expect(res.body).toEqual(unknown.body);
    }
  });

  test('login input that is not text is a clean 422, not a crash', async () => {
    const res = await request(app).post('/api/auth/login').send({ loginKey: { $ne: null }, password: ['x'] });
    expect(res.status).toBe(422);
  });

  test('a soft-deleted student can no longer sign in, and their old token stops working', async () => {
    const signUp = await signUpStudent(app, outbox, { studentNumber: '202301004', email: 'gone@example.com' });
    const lecturer = await createLecturer(pool, { email: 'del@example.com', employeeId: 'EMP-9103' });
    const lecturerToken = await loginAs(app, lecturer.email, lecturer.password);

    const del = await request(app).delete(`/api/students/${signUp.body.student.studentId}`).set('Authorization', `Bearer ${lecturerToken}`);
    expect(del.status).toBe(200);

    expect((await request(app).post('/api/auth/login').send({ loginKey: 'gone@example.com', password: 'Str0ngPass!' })).status).toBe(401);
    expect((await request(app).get('/api/students/me').set('Authorization', `Bearer ${signUp.body.token}`)).status).toBe(401);
  });

  test('unauthenticated requests are rejected with 401', async () => {
    const res = await request(app).get('/api/students/me');
    expect(res.status).toBe(401);
    expect(res.body.error).toBe('UNAUTHORIZED');
  });
});
