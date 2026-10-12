// utils/serialize.js
//
// Keeps the JSON the API returns independent of the exact column names in
// MySQL, and makes sure we never accidentally send back things like
// password_hash.

const { maskNrc } = require('./validators');

// By default only a MASKED NRC is returned — list screens never need the full
// national ID number. Pass { full: true } for single-record views (a lecturer
// opening one student, a student viewing their own profile), which add the
// full nrc and dateOfBirth.
function serializeStudent(row, { full = false } = {}) {
  if (!row) return null;
  return {
    studentId: row.student_id,
    studentNumber: row.student_number,
    fullName: row.full_name,
    nrcMasked: maskNrc(row.nrc),
    ...(full ? { nrc: row.nrc, dateOfBirth: row.date_of_birth } : {}),
    programmeCode: row.programme_code || null, // present when the query joined programmes
    groupCode: row.group_code || null, // null = Unassigned
    active: !!row.active,
    version: row.version,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function serializeAccount(row) {
  if (!row) return null;
  return {
    accountId: row.account_id,
    role: row.role,
    email: row.email,
    phone: row.phone || null,
    displayName: row.display_name || null,
    studentId: row.student_id || null,
    employeeId: row.employee_id || null,
  };
}

// One serializer for both kinds of change request; each kind adds its own fields.
function serializeChangeRequest(row) {
  if (!row) return null;
  return {
    requestId: row.request_id,
    kind: row.kind,
    studentId: row.student_id,
    studentName: row.full_name || undefined,
    studentNumber: row.student_number || undefined,
    ...(row.kind === 'GROUP'
      ? { fromGroupCode: row.from_group_code || null, toGroupCode: row.to_group_code || null }
      : { oldNumber: row.old_number, newNumber: row.new_number }),
    reason: row.reason,
    status: row.status,
    requestedAt: row.requested_at,
    resolvedAt: row.resolved_at,
  };
}

module.exports = {
  serializeStudent,
  serializeAccount,
  serializeChangeRequest,
};
