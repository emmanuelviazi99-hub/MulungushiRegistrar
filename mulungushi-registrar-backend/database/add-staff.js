// database/add-staff.js
//
// Approves a lecturer to sign up. Run by whoever administers the server:
//
//   npm run staff:add -- --email x.person@mulungushi.edu.zm --employee-id EMP-2001 --name "Full Name"
//
// This is deliberately a command-line tool and NOT an API endpoint: a lecturer
// account can edit the whole roster, so the list of people allowed to create one
// must not be editable by anyone who merely has a login.
//
// The lecturer then opens the app, chooses "Lecturer sign-up", enters this email
// and employee ID, and receives a verification code at that email address.

require('dotenv').config();
const pool = require('../config/database');
const v = require('../utils/validators');

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i !== -1 ? process.argv[i + 1] : undefined;
}

async function main() {
  const email = v.normaliseEmail(arg('email'));
  const employeeId = v.normaliseEmployeeId(arg('employee-id'));
  const name = String(arg('name') || '').trim();

  const problems = [];
  if (!v.isValidEmail(email)) problems.push('--email must be a valid email address');
  if (!v.isValidEmployeeId(employeeId)) problems.push('--employee-id must look like EMP-2001');
  if (!v.isValidName(name)) problems.push('--name must be 2-100 letters');
  if (problems.length) {
    console.error(`${problems.join('\n')}\n\nUsage: npm run staff:add -- --email x@mulungushi.edu.zm --employee-id EMP-2001 --name "Full Name"`);
    process.exit(1);
  }

  try {
    await pool.query('INSERT INTO staff_roster (employee_id, email, full_name) VALUES (?, ?, ?)', [employeeId, email, name]);
    console.log(`Added ${name} (${employeeId}, ${email}). They can now sign up as a lecturer.`);
  } catch (err) {
    if (err.code === 'ER_DUP_ENTRY') {
      console.error('That email or employee ID is already on the staff list.');
      process.exit(1);
    }
    throw err;
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
