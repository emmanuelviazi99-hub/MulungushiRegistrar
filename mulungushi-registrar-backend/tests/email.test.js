// tests/email.test.js
//
// Verification codes delivered by EMAIL through Nodemailer — the real code
// path, against a real SMTP server running inside the test (smtp-server, with
// a login and STARTTLS), not a mock. Proves that:
//   - the code arrives in the mailbox and is the only place it appears
//   - the emailed code is what verifies the sign-up
//   - a broken mail server gives DELIVERY_FAILED and leaves no usable code
//   - password reset emails only go to registered addresses
//   - the server refuses to start with incomplete SMTP settings

process.env.NODE_ENV = 'test';

const request = require('supertest');
const { SMTPServer } = require('smtp-server');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { identityOf } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const delivery = require('../services/delivery.service');
const { assertConfig } = require('../config/env');

const SMTP_USER = 'registrar@test.example';
const SMTP_PASS = 'smtp-test-password';
const inbox = []; // { to, raw }
let smtp;

function useSmtp({ pass = SMTP_PASS } = {}) {
  Object.assign(process.env, {
    TEST_CODE_DELIVERY: 'email',
    SMTP_HOST: '127.0.0.1',
    SMTP_PORT: String(smtp.server.address().port),
    SMTP_SECURE: 'false',
    SMTP_USER,
    SMTP_PASS: pass,
    SMTP_FROM: 'Mulungushi Registrar <no-reply@test.example>',
  });
}
const mailTo = (address) => inbox.filter((m) => m.to.includes(address));
const codeIn = (mail) => (mail.raw.match(/MU-[A-HJ-NP-Z2-9]{6}/) || [])[0];

beforeAll(async () => {
  await resetDb();
  smtp = new SMTPServer({
    authOptional: false,
    disabledCommands: [], // STARTTLS stays on, like a real server (self-signed certificate)
    logger: false,
    onAuth(auth, session, cb) {
      if (auth.username === SMTP_USER && auth.password === SMTP_PASS) return cb(null, { user: auth.username });
      return cb(new Error('535 Invalid login'));
    },
    onData(stream, session, cb) {
      let raw = '';
      stream.on('data', (chunk) => { raw += chunk; });
      stream.on('end', () => {
        inbox.push({ to: session.envelope.rcptTo.map((r) => r.address), from: session.envelope.mailFrom.address, raw });
        cb();
      });
    },
  });
  await new Promise((resolve) => smtp.listen(0, '127.0.0.1', resolve));
  useSmtp();
});
afterAll(async () => {
  delivery.closeTransport();
  delete process.env.TEST_CODE_DELIVERY;
  await new Promise((resolve) => smtp.close(resolve));
  await pool.end();
});

describe('sign-up codes by email (Nodemailer + SMTP)', () => {
  test('the code arrives by email, is never in the API response, and the emailed code completes sign-up', async () => {
    const email = 'chanda.mwansa@student.example';
    const asked = await request(app)
      .post('/api/auth/register/request')
      .send({ fullName: 'Chanda Mwansa', studentNumber: '202301001', ...identityOf('202301001'), email, phone: '0977123456' });
    expect(asked.status).toBe(200);
    expect(asked.body.maskedEmail).toBe('c***@student.example');

    const mails = mailTo(email);
    expect(mails).toHaveLength(1);
    const code = codeIn(mails[0]);
    expect(code).toMatch(/^MU-[A-Z2-9]{6}$/);
    expect(mails[0].from).toBe('no-reply@test.example');
    expect(mails[0].raw).toMatch(/Subject: MU-[A-Z2-9]{6} is your Mulungushi Registrar code/);
    expect(mails[0].raw).toContain('text/html'); // a readable HTML version as well as plain text
    expect(JSON.stringify(asked.body)).not.toContain(code);
    expect(delivery.outbox).toHaveLength(0); // the real channel was used, not the test outbox

    const verified = await request(app).post('/api/auth/register/verify').send({ email, code });
    expect(verified.status).toBe(200);
    expect(verified.body.fullName).toBe('Chanda Mwansa');
    const done = await request(app).post('/api/auth/register/complete').send({ email, password: 'Str0ngPass!' });
    expect(done.status).toBe(201);
    expect(done.body.account).toMatchObject({ role: 'STUDENT', email });
  });

  test('a lecturer on the staff list gets their code at the staff-list address', async () => {
    const email = 'm.chilufya@mulungushi.edu.zm';
    const asked = await request(app)
      .post('/api/auth/staff/request')
      .send({ fullName: 'Mutale Chilufya', email, employeeId: 'EMP-1002' });
    expect(asked.status).toBe(200);
    const code = codeIn(mailTo(email)[0]);
    expect((await request(app).post('/api/auth/staff/verify').send({ email, code })).status).toBe(200);
    const done = await request(app).post('/api/auth/staff/complete').send({ email, password: 'Lecturer#Pass1' });
    expect(done.status).toBe(201);
    expect(done.body.account).toMatchObject({ role: 'LECTURER', employeeId: 'EMP-1002' });
  });

  test('if the mail server refuses us, the answer is DELIVERY_FAILED and no usable code is left behind', async () => {
    const email = 'bwalya.phiri@student.example';
    const body = { fullName: 'Bwalya Phiri', studentNumber: '202301002', ...identityOf('202301002'), email, phone: '0977123457' };

    useSmtp({ pass: 'wrong-password' });
    const failed = await request(app).post('/api/auth/register/request').send(body);
    expect(failed.status).toBe(502);
    expect(failed.body.error).toBe('DELIVERY_FAILED');
    expect(mailTo(email)).toHaveLength(0);
    const [[{ n }]] = await pool.query('SELECT COUNT(*) AS n FROM verification_codes WHERE email = ?', [email]);
    expect(n).toBe(0); // discarded, so it neither works nor triggers the 30-second resend wait

    useSmtp(); // mail server fixed: asking again works straight away
    const retried = await request(app).post('/api/auth/register/request').send(body);
    expect(retried.status).toBe(200);
    expect(mailTo(email)).toHaveLength(1);
  });

  test('password reset emails go only to registered addresses, with an identical reply either way', async () => {
    const before = inbox.length;
    const known = await request(app).post('/api/auth/password/request').send({ email: 'chanda.mwansa@student.example' });
    const unknown = await request(app).post('/api/auth/password/request').send({ email: 'nobody@student.example' });
    expect(known.status).toBe(200);
    expect(unknown.status).toBe(200);
    expect(Object.keys(known.body).sort()).toEqual(Object.keys(unknown.body).sort());
    expect(inbox.length).toBe(before + 1);
    const resetMail = mailTo('chanda.mwansa@student.example').at(-1).raw.replace(/=\r?\n/g, ''); // undo quoted-printable line wrapping
    expect(resetMail).toContain('reset your Mulungushi Registrar password');
    expect(mailTo('nobody@student.example')).toHaveLength(0);
  });
});

describe('startup checks for code delivery', () => {
  const base = { NODE_ENV: 'development', JWT_SECRET: 'x'.repeat(40) };

  test('email is the default channel, and it refuses to start without SMTP settings', () => {
    expect(delivery.parseChannels(undefined)).toEqual(['email']);
    expect(() => assertConfig({ ...base })).toThrow(/SMTP_HOST/);
    expect(() => assertConfig({ ...base, SMTP_HOST: 'smtp.gmail.com', SMTP_USER: 'me@gmail.com' })).toThrow(/SMTP_PASS/);
    expect(() =>
      assertConfig({ ...base, SMTP_HOST: 'smtp.gmail.com', SMTP_USER: 'me@gmail.com', SMTP_PASS: 'abcdabcdabcdabcd' })
    ).not.toThrow();
  });

  test('console delivery is for local development only, and unknown channels are refused', () => {
    expect(() => assertConfig({ ...base, CODE_DELIVERY: 'console' })).not.toThrow();
    expect(() => assertConfig({ ...base, NODE_ENV: 'production', CODE_DELIVERY: 'console' })).toThrow(/console/);
    expect(() => assertConfig({ ...base, CODE_DELIVERY: 'pigeon' })).toThrow(/unknown channel/);
  });
});
