// services/account.service.js
//
// Loads what the client needs to know about a signed-in account, in one
// shape for students and lecturers alike.

const pool = require('../config/database');

/** The account row plus display name / employee id from the roster it is linked to. */
async function loadProfile(accountId, db = pool) {
  const [rows] = await db.query(
    `SELECT a.*, COALESCE(s.full_name, f.full_name) AS display_name, f.employee_id AS employee_id
       FROM accounts a
       LEFT JOIN students s     ON s.student_id = a.student_id
       LEFT JOIN staff_roster f ON f.staff_id   = a.staff_id
      WHERE a.account_id = ?`,
    [accountId]
  );
  return rows[0] || null;
}

module.exports = { loadProfile };
