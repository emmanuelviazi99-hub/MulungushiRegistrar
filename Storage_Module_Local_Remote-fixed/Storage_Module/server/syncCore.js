// Sync rules, no framework needed. The same rules as the backend's services/sync.service.js:
//   - per student id, the copy with the larger updatedAt wins; an equal or older upload
//     changes nothing, so a retried upload is harmless
//   - every record is validated: firstName and lastName separately, number = a STRING of
//     exactly 9 digits (leading zeros kept), a known programme, a valid email when present
//   - a student number belongs to one id only, so a retry or a second phone can never
//     create a duplicate student
//   - invalid records are returned in `rejected` with the reason and never stored; the valid
//     records in the same upload are still saved
//   - phone and password fields are never stored (the system no longer collects them)
const fs = require('fs');

const PROGRAMMES = new Set(['CS', 'IT', 'DS']);
const ID_RE = /^[A-Za-z0-9._:-]{1,64}$/;
const NUMBER_RE = /^[0-9]{9}$/;
const NAME_RE = /^[\p{L}][\p{L}\s'.-]*$/u;
const EMAIL_RE = /^[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}$/;

/** True only for a string of exactly nine digits. */
function isValidStudentNumber(v) {
  return typeof v === 'string' && NUMBER_RE.test(v);
}
function isValidNamePart(v) {
  return typeof v === 'string' && v.trim().length >= 2 && v.trim().length <= 50 && NAME_RE.test(v.trim());
}

/** Checks the overall shape of an upload. Returns an error message, or null. */
function validate(list) {
  if (!Array.isArray(list)) return 'students must be an array';
  if (list.length > 500) return 'at most 500 students per upload';
  return null;
}

/** Validates one record. Returns { record } or { error, message }. */
function normalise(raw) {
  const bad = (message) => ({ error: 'VALIDATION_ERROR', message });
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return bad('each student must be an object');
  if (typeof raw.id !== 'string' || !ID_RE.test(raw.id)) return bad('id: must be 1-64 letters, digits or . _ : -');
  if (!Number.isSafeInteger(raw.updatedAt) || raw.updatedAt < 0) return bad('updatedAt: must be a whole number of milliseconds');
  if (!isValidNamePart(raw.firstName)) return bad('firstName: must be 2-50 letters');
  if (!isValidNamePart(raw.lastName)) return bad('lastName: must be 2-50 letters');
  if (!isValidStudentNumber(raw.number)) return bad('number: must be exactly 9 digits (a string)');
  const programme = typeof raw.programme === 'string' ? raw.programme.trim().toUpperCase() : '';
  if (!PROGRAMMES.has(programme)) return bad('programme: unknown programme code');
  let email = null;
  if (raw.email !== undefined && raw.email !== null && raw.email !== '') {
    email = String(raw.email).trim().toLowerCase();
    if (email.length > 254 || email.includes('..') || !EMAIL_RE.test(email)) return bad('email: must be a valid email address');
  }
  const { phone, password, ...rest } = raw; // eslint-disable-line no-unused-vars
  const firstName = raw.firstName.trim();
  const lastName = raw.lastName.trim();
  return { record: { ...rest, firstName, lastName, name: `${firstName} ${lastName}`, programme, email, active: raw.active !== false } };
}

function createStore(file) {
  const db = new Map();
  if (fs.existsSync(file)) {
    try { for (const s of JSON.parse(fs.readFileSync(file, 'utf8'))) db.set(s.id, s); }
    catch (e) { throw new Error('Could not read ' + file + ': ' + e.message); }
  }
  function save() {
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify([...db.values()], null, 2));
    fs.renameSync(tmp, file); // atomic: a crash never leaves half a file
  }
  return {
    list: () => [...db.values()],
    // Applies incoming changes; returns the full list the phone should now hold, and the refused records.
    sync(incoming) {
      let changed = false;
      const rejected = [];
      for (const raw of incoming) {
        const id = raw && typeof raw.id === 'string' ? raw.id : null;
        const checked = normalise(raw);
        if (checked.error) { rejected.push({ id, error: checked.error, message: checked.message }); continue; }
        const s = checked.record;
        const owner = [...db.values()].find((x) => x.number === s.number && x.id !== s.id);
        if (owner) {
          rejected.push({ id, error: 'DUPLICATE_STUDENT_NUMBER', message: `number: ${s.number} already belongs to another student record` });
          continue;
        }
        const cur = db.get(s.id);
        if (!cur || s.updatedAt > cur.updatedAt) { db.set(s.id, s); changed = true; }
      }
      if (changed) save();
      return { students: [...db.values()], rejected };
    },
  };
}

module.exports = { createStore, validate, normalise, isValidStudentNumber };
