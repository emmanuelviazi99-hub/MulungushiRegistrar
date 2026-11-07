// scripts/remove-demo-students.js
//
// Before importing the real class list: removes the demo students that
// `npm run db:setup` adds (the student numbers in database/seed.sql), so they are
// not mixed into G01-G04. Only demo students WITHOUT an account are removed, the
// same way the app's "Remove student" does (their numbers stay reserved).
// Shows what it would remove; add --apply to remove.
//
//   npm run roster:remove-demo             (preview)
//   npm run roster:remove-demo -- --apply

require('dotenv').config();
const fs = require('fs');
const path = require('path');
const pool = require('../config/database');
const studentService = require('../services/student.service');

async function main() {
  const apply = process.argv.includes('--apply');
  const seed = fs.readFileSync(path.join(__dirname, '..', 'database', 'seed.sql'), 'utf8');
  const numbers = [...new Set((seed.match(/'(\d{9})'/g) || []).map((x) => x.slice(1, -1)))];
  if (!numbers.length) {
    console.log('No demo student numbers found in database/seed.sql.');
    return;
  }
  const [rows] = await pool.query(
    `SELECT s.student_id, s.student_number, s.full_name, (a.account_id IS NOT NULL) AS has_account
       FROM students s LEFT JOIN accounts a ON a.student_id = s.student_id
      WHERE s.student_number IN (?) AND s.deleted_at IS NULL
      ORDER BY s.student_number`,
    [numbers]
  );
  const removable = rows.filter((r) => !r.has_account);
  const kept = rows.filter((r) => r.has_account);
  console.log(`Demo students still on the roster: ${rows.length}`);
  console.log(`  ${apply ? 'Removing' : 'Would remove'} (no account): ${removable.length}`);
  if (kept.length) console.log(`  Kept because someone signed up with them: ${kept.map((r) => r.student_number).join(', ')}`);
  if (!apply) {
    if (removable.length) console.log('\nTo remove them, run: npm run roster:remove-demo -- --apply');
    return;
  }
  for (const r of removable) await studentService.deleteStudent(r.student_id);
  console.log('Done.');
}

main()
  .catch((err) => {
    console.error(`FAILED: ${err.message}`);
    process.exitCode = 1;
  })
  .finally(() => pool.end());
