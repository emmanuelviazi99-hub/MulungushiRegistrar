// database/seed-accounts.js
//
// Creates a ready-to-use demo LECTURER login so you can sign in immediately
// after loading the database. (A password needs a bcrypt hash, which SQL
// alone cannot make — that is why this is a script and not part of seed.sql.)
//
//   npm run seed:accounts
//
// The lecturer is the staff-roster row for SEED_LECTURER_EMAIL (default:
// b.nyirenda@mulungushi.edu.zm, employee ID EMP-1001). Everyone else — students
// and the other lecturers — signs up through the verification-code flow.

require('dotenv').config();
const bcrypt = require('bcrypt');
const pool = require('../config/database');
const { bcryptRounds } = require('../config/env');

async function main() {
  const email = (process.env.SEED_LECTURER_EMAIL || 'b.nyirenda@mulungushi.edu.zm').trim().toLowerCase();
  const employeeId = (process.env.SEED_LECTURER_EMPLOYEE_ID || 'EMP-1001').trim().toUpperCase();
  const name = process.env.SEED_LECTURER_NAME || 'Bwalya Nyirenda';
  const password = process.env.SEED_LECTURER_PASSWORD;

  if (!password || password.length < 8) {
    console.error('Set SEED_LECTURER_PASSWORD (8+ characters) in .env first.');
    process.exit(1);
  }

  // Make sure the staff-roster row exists (seed.sql normally created it already).
  await pool.query(
    `INSERT INTO staff_roster (employee_id, email, full_name) VALUES (?, ?, ?)
     ON DUPLICATE KEY UPDATE full_name = VALUES(full_name)`,
    [employeeId, email, name]
  );
  const [[staff]] = await pool.query('SELECT staff_id FROM staff_roster WHERE employee_id = ?', [employeeId]);

  const [existing] = await pool.query('SELECT account_id FROM accounts WHERE staff_id = ?', [staff.staff_id]);
  if (existing.length > 0) {
    console.log(`A login for ${email} already exists — nothing to do.`);
  } else {
    const hash = await bcrypt.hash(password, bcryptRounds());
    await pool.query("INSERT INTO accounts (email, password_hash, staff_id) VALUES (?, ?, ?)", [
      email,
      hash,
      staff.staff_id,
    ]);
    console.log('Demo lecturer account created:');
    console.log(`  sign in with: ${email}`);
    console.log('  password:     the SEED_LECTURER_PASSWORD from your .env  (demo only - change it for anything real)');
  }
  await pool.end();
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
