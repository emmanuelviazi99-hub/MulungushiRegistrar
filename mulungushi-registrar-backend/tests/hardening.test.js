// tests/hardening.test.js
//
// The per-IP rate limits (middleware/rateLimit.js). They are switched off for
// the other test files, which make hundreds of calls from one address; this
// file turns them on BEFORE the app is loaded (jest gives each test file its
// own module registry, so this does not leak into the other files).

process.env.NODE_ENV = 'test';
process.env.RATE_LIMIT_IN_TEST = '1';

const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');

beforeAll(async () => {
  await resetDb();
});
afterAll(async () => {
  delete process.env.RATE_LIMIT_IN_TEST;
  await pool.end();
});

test('asking for codes is limited to 10 per 15 minutes per address (each one sends an email)', async () => {
  const ask = () => request(app).post('/api/auth/password/request').send({ email: 'someone@example.com' });
  for (let i = 0; i < 10; i += 1) expect((await ask()).status).toBe(200);
  const blocked = await ask();
  expect(blocked.status).toBe(429);
  expect(blocked.body.error).toBe('RATE_LIMITED');
  expect(blocked.headers).toHaveProperty('ratelimit-reset'); // tells the app when to try again
});

test('login attempts are limited to 30 per 15 minutes per address', async () => {
  const attempt = () => request(app).post('/api/auth/login').send({ loginKey: 'nobody@example.com', password: 'wrong-password' });
  for (let i = 0; i < 30; i += 1) expect((await attempt()).status).toBe(401);
  expect((await attempt()).status).toBe(429);
});
