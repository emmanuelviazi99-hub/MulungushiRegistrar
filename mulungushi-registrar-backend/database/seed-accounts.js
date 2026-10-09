// database/seed-accounts.js
//
// Why this is a .js file and not part of seed.sql:
// passwords must be hashed with bcrypt, and bcrypt only exists on the
// Node side (plain SQL has no bcrypt function). Run this AFTER
// schema.sql and seed.sql have been applied:
//
//   npm install
//   mysql -u root -p mulungushi_registrar < database/schema.sql
//   mysql -u root -p mulungushi_registrar < database/seed.sql
//   npm run seed:accounts
//
// It is safe to re-run: it skips the account if the username already exists.

require('dotenv').config();
const bcrypt = require('bcrypt');
const pool = require('../config/database');

const USERNAME = process.env.SEED_LECTURER_USERNAME || 'lecturer1';
const PASSWORD = process.env.SEED_LECTURER_PASSWORD || 'Lecturer#2025';
const BCRYPT_ROUNDS = 12;

async function main() {
  const [existing] = await pool.query('SELECT account_id FROM accounts WHERE username = ?', [USERNAME]);
  if (existing.length > 0) {
    console.log(`Account "${USERNAME}" already exists — nothing to do.`);
    process.exit(0);
  }

  const passwordHash = await bcrypt.hash(PASSWORD, BCRYPT_ROUNDS);
  await pool.query('INSERT INTO accounts (username, password_hash, role, student_id) VALUES (?, ?, ?, NULL)', [
    USERNAME,
    passwordHash,
    'LECTURER',
  ]);

  console.log('Lecturer account created:');
  console.log(`  username: ${USERNAME}`);
  console.log(`  password: ${PASSWORD}  (demo only — change this for anything beyond marking)`);
  process.exit(0);
}

main().catch((err) => {
  console.error('Seeding failed:', err);
  process.exit(1);
});
