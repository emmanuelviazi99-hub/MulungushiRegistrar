// tests/password-change.test.js
//
// "Change password" in the app's Edit my details: PUT /api/auth/password,
// signed in, with the current password and a new one.

process.env.NODE_ENV = 'test';

const request = require('supertest');
const { resetDb, TEST_DB_NAME } = require('./helpers/resetDb');
const { signUpStudent } = require('./helpers/fixtures');

process.env.DB_NAME = TEST_DB_NAME;

const app = require('../server');
const pool = require('../config/database');
const { outbox } = require('../services/delivery.service');

const change = (token, body) => request(app).put('/api/auth/password').set('Authorization', `Bearer ${token}`).send(body);
const login = (password) => request(app).post('/api/auth/login').send({ loginKey: '202301010', password });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let token;

beforeAll(async () => {
  await resetDb();
  const done = await signUpStudent(app, outbox, { studentNumber: '202301010', email: 'pw@test.example', password: 'Old#Password1' });
  token = done.body.token;
});

afterAll(async () => {
  await pool.end();
});

describe('PUT /api/auth/password', () => {
  test('a wrong current password is refused and nothing changes', async () => {
    const res = await change(token, { currentPassword: 'not-it', newPassword: 'New#Password2' });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe('WRONG_PASSWORD');
    expect((await login('Old#Password1')).status).toBe(200);
  });

  test('the new password must be 8-72 characters and different from the current one', async () => {
    const short = await change(token, { currentPassword: 'Old#Password1', newPassword: 'short' });
    expect(short.status).toBe(422);
    expect(short.body.message).toMatch(/^newPassword/);
    const same = await change(token, { currentPassword: 'Old#Password1', newPassword: 'Old#Password1' });
    expect(same.status).toBe(422);
    expect(same.body.message).toMatch(/different/);
    const missing = await change(token, { newPassword: 'New#Password2' });
    expect(missing.status).toBe(422);
    expect(missing.body.message).toMatch(/^currentPassword/);
  });

  test('signed in only', async () => {
    const res = await request(app).put('/api/auth/password').send({ currentPassword: 'Old#Password1', newPassword: 'New#Password2' });
    expect(res.status).toBe(401);
  });

  test('works: old password stops, new one signs in, this phone gets a working token, other phones are signed out', async () => {
    const otherPhone = (await login('Old#Password1')).body.token;
    await sleep(1100); // tokens carry whole seconds: make the change land in a later second than otherPhone's token

    const res = await change(token, { currentPassword: 'Old#Password1', newPassword: 'New#Password2' });
    expect(res.status).toBe(200);
    expect(res.body.token).toBeTruthy();
    expect(res.body.student.studentNumber).toBe('202301010');

    expect((await login('Old#Password1')).status).toBe(401);
    expect((await login('New#Password2')).status).toBe(200);

    const me = await request(app).get('/api/students/me').set('Authorization', `Bearer ${res.body.token}`);
    expect(me.status).toBe(200); // the token it gave back works
    const stale = await request(app).get('/api/students/me').set('Authorization', `Bearer ${otherPhone}`);
    expect(stale.status).toBe(401); // the other phone's older token does not
    token = res.body.token;
  });

  test('guessing counts towards the sign-in lock: 5 wrong current passwords lock the account', async () => {
    for (let i = 0; i < 5; i++) {
      const res = await change(token, { currentPassword: `guess-${i}`, newPassword: 'Another#Pass3' });
      expect(res.status).toBe(400);
    }
    const right = await change(token, { currentPassword: 'New#Password2', newPassword: 'Another#Pass3' });
    expect(right.status).toBe(400); // locked: even the right one is refused for 15 minutes
    expect((await login('New#Password2')).status).toBe(401);
  });
});
