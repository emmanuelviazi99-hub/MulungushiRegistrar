// database/add-staff.js
//
// Creates a lecturer's login. Run by whoever administers the server:
//
//   npm run staff:add -- --email x.person@mulungushi.edu.zm --employee-id EMP-2001 --name "Full Name"
//   npm run staff:add -- --email ... --employee-id ... --name "..." --password "Their#Password1"
//
// Lecturers do not sign up in the app (the Register screen is for students only),
// because a lecturer account can edit the whole roster. This command puts the
// person on the staff list and creates their login in one go. Without --password
// it makes a temporary one and prints it; the lecturer signs in with it and can set
// their own with "Forgot password" on the sign-in screen (a code to their email).
//
// Running it again for someone who already has a login changes nothing; add
// --password to give them a new one (their old sign-ins stop working).
//
// This is deliberately a command-line tool and NOT an API endpoint: the list of
// people allowed to be lecturers must not be editable by anyone who merely has a login.

require('dotenv').config();
const crypto = require('crypto');
const bcrypt = require('bcrypt');
const pool = require('../config/database');
const { bcryptRounds } = require('../config/env');
const v = require('../utils/validators');

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i !== -1 ? process.argv[i + 1] : undefined;
}

/** 12 characters from an alphabet without look-alikes (0/O, 1/l/I), plus a digit and a symbol. */
function temporaryPassword() {
  const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz';
  let out = '';
  for (let i = 0; i < 9; i++) out += letters[crypto.randomInt(letters.length)];
  return `${out}${crypto.randomInt(2, 10)}#${crypto.randomInt(2, 10)}`;
}

async function main() {
  const email = v.normaliseEmail(arg('email'));
  const employeeId = v.normaliseEmployeeId(arg('employee-id'));
  const name = String(arg('name') || '').trim();
  const given = arg('password');

  const problems = [];
  if (!v.isValidEmail(email)) problems.push('--email must be a valid email address');
  if (!v.isValidEmployeeId(employeeId)) problems.push('--employee-id must look like EMP-2001');
  if (!v.isValidName(name)) problems.push('--name must be 2-100 letters');
  if (given !== undefined && (given.length < 8 || given.length > 72)) problems.push('--password must be 8-72 characters');
  if (problems.length) {
    console.error(`${problems.join('\n')}\n\nUsage: npm run staff:add -- --email x@mulungushi.edu.zm --employee-id EMP-2001 --name "Full Name" [--password "..."]`);
    process.exit(1);
  }

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();

    // The staff-list row: new, or the one already there for this employee ID.
    const [[byId]] = await conn.query('SELECT staff_id, email FROM staff_roster WHERE employee_id = ? FOR UPDATE', [employeeId]);
    const [[byEmail]] = await conn.query('SELECT staff_id, employee_id FROM staff_roster WHERE email = ? FOR UPDATE', [email]);
    if ((byId && byId.email !== email) || (byEmail && byEmail.employee_id !== employeeId)) {
      throw new Error('The staff list already has this employee ID with another email, '
        + 'or this email with another employee ID. Check the details.');
    }
    let staffId = byId && byId.staff_id;
    if (!staffId) {
      const [res] = await conn.query('INSERT INTO staff_roster (employee_id, email, full_name) VALUES (?, ?, ?)', [employeeId, email, name]);
      staffId = res.insertId;
    }

    const [[account]] = await conn.query('SELECT account_id FROM accounts WHERE staff_id = ? FOR UPDATE', [staffId]);
    const [[emailTaken]] = await conn.query('SELECT account_id FROM accounts WHERE email = ? AND (staff_id IS NULL OR staff_id <> ?)', [email, staffId]);
    if (emailTaken) throw new Error('Another account (a student) already signs in with that email. Use a different email.');

    if (account && given === undefined) {
      await conn.commit();
      console.log(`${name} (${employeeId}) already has a login: ${email}. Nothing changed.`);
      console.log('To give them a new password, run this again with --password "...".');
      return;
    }

    const password = given !== undefined ? given : temporaryPassword();
    const hash = await bcrypt.hash(password, bcryptRounds());
    if (account) {
      // a new password: older sign-ins stop working, and any lock is lifted
      await conn.query(
        `UPDATE accounts SET password_hash = ?, password_changed_at = CURRENT_TIMESTAMP(3),
                failed_logins = 0, locked_until = NULL, active = 1 WHERE account_id = ?`,
        [hash, account.account_id]);
    } else {
      await conn.query('INSERT INTO accounts (email, password_hash, staff_id) VALUES (?, ?, ?)', [email, hash, staffId]);
    }
    await conn.commit();

    console.log(`${account ? 'New password set' : 'Lecturer login created'} for ${name} (${employeeId}).`);
    console.log(`  sign in with: ${email}`);
    if (given === undefined) {
      console.log(`  temporary password: ${password}`);
      console.log('  Give it to them privately. They can choose their own with "Forgot password" on the sign-in screen.');
    } else {
      console.log('  password: the one you gave with --password');
    }
  } catch (err) {
    await conn.rollback().catch(() => {});
    throw err;
  } finally {
    conn.release();
    await pool.end();
  }
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
