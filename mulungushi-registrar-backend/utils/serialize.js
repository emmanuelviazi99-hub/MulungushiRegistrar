// utils/serialize.js
//
// Keeps the JSON the API returns independent of the exact column names in
// MySQL, and makes sure we never accidentally send back things like
// password_hash or deletion_marker.

function serializeStudent(row) {
  if (!row) return null;
  return {
    studentId: row.student_id,
    studentNumber: row.student_number,
    fullName: row.full_name,
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
    username: row.username,
    role: row.role,
    studentId: row.student_id,
  };
}

function serializeGroupRequest(row) {
  if (!row) return null;
  return {
    requestId: row.request_id,
    studentId: row.student_id,
    studentName: row.full_name || undefined,
    studentNumber: row.student_number || undefined,
    fromGroupCode: row.from_group_code || null,
    toGroupCode: row.to_group_code || null,
    reason: row.reason,
    status: row.status,
    requestedAt: row.requested_at,
    resolvedAt: row.resolved_at,
  };
}

function serializeNumberCorrectionRequest(row) {
  if (!row) return null;
  return {
    requestId: row.request_id,
    studentId: row.student_id,
    studentName: row.full_name || undefined,
    oldNumber: row.old_number,
    newNumber: row.new_number,
    reason: row.reason,
    status: row.status,
    requestedAt: row.requested_at,
    resolvedAt: row.resolved_at,
  };
}

module.exports = {
  serializeStudent,
  serializeAccount,
  serializeGroupRequest,
  serializeNumberCorrectionRequest,
};
