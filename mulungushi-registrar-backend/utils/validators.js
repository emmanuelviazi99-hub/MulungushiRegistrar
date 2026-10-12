// utils/validators.js
//
// One place for the field rules that both the request validators
// (middleware/validation.js) and the services (services/student.service.js)
// need. The phone / NRC / code / password rules deliberately mirror the
// Android app's ContactValidator.java so a value the app accepts is never
// rejected by the server for a formatting reason, and vice versa.

const NAME_RE = /^[\p{L}][\p{L}\s'.-]*$/u;
const EMAIL_RE = /^[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}$/;
const PHONE_RE = /^\+260\d{9}$/;
const NRC_RE = /^\d{6}\/\d{2}\/\d$/;
const CODE_RE = /^MU-[A-HJ-NP-Z2-9]{6}$/; // the alphabet code.service uses: no 0/O/1/I
const EMPLOYEE_ID_RE = /^[A-Z0-9][A-Z0-9-]{2,19}$/;
const STUDENT_NUMBER_RE = /^\d{9}$/;

function isValidName(raw) {
  const t = String(raw ?? '').trim();
  return t.length >= 2 && t.length <= 100 && NAME_RE.test(t);
}

function isValidStudentNumber(raw) {
  return STUDENT_NUMBER_RE.test(String(raw ?? ''));
}

// --- email -----------------------------------------------------------------
function normaliseEmail(raw) {
  return String(raw ?? '').trim().toLowerCase();
}
function isValidEmail(raw) {
  const t = String(raw ?? '').trim();
  return t.length > 0 && t.length <= 254 && !t.includes('..') && EMAIL_RE.test(t);
}
/** chanda.mwansa@example.com -> c***@example.com */
function maskEmail(email) {
  const t = String(email ?? '').trim();
  const at = t.indexOf('@');
  return at <= 0 ? t : `${t[0]}***${t.slice(at)}`;
}

// --- phone (Zambia, +260 followed by 9 digits) --------------------------------
function normalisePhone(raw) {
  const t = String(raw ?? '').replace(/[\s\-()]/g, '');
  if (t.startsWith('00260')) return `+${t.slice(2)}`;
  if (t.startsWith('260') && !t.startsWith('+')) return `+${t}`;
  if (t.startsWith('0') && t.length === 10) return `+260${t.slice(1)}`;
  return t;
}
function isValidPhone(raw) {
  return PHONE_RE.test(normalisePhone(raw));
}

// --- NRC (123456/10/1) --------------------------------------------------------
// Accepts 123456/10/1, 123456101, 123456-10-1 ... but nothing containing
// letters, so a typo can never be silently "fixed" by dropping characters.
function normaliseNrc(raw) {
  const s = String(raw ?? '').trim();
  if (!/^[\d\s/-]+$/.test(s)) return s;
  const digits = s.replace(/\D/g, '');
  if (digits.length !== 9) return s;
  return `${digits.slice(0, 6)}/${digits.slice(6, 8)}/${digits.slice(8)}`;
}
function isValidNrc(raw) {
  return NRC_RE.test(normaliseNrc(raw));
}
// Masks an NRC for list views, which never need the full number:
// 700001/10/1 becomes 70 + four stars + the slashes, ending in the check digit.
function maskNrc(nrc) {
  return NRC_RE.test(String(nrc ?? '')) ? `${nrc.slice(0, 2)}****/**/${nrc.slice(-1)}` : null;
}

// --- date of birth (YYYY-MM-DD, a real date in the past, not > 100 years ago) --
function isValidDob(raw, nowArg) {
  // `nowArg` exists so tests can pin "today". Ignore anything that is not a Date
  // (express-validator passes a metadata object as the 2nd argument to custom validators).
  const now = nowArg instanceof Date ? nowArg : new Date();
  if (typeof raw !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return false;
  const [y, m, d] = raw.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCFullYear() !== y || dt.getUTCMonth() !== m - 1 || dt.getUTCDate() !== d) return false;
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  return dt.getTime() < today && y >= now.getUTCFullYear() - 100;
}

// --- verification code (MU-7K4Q style) ---------------------------------------------
// People type codes in many ways: "mu-7k4qxp", "7K4QXP", "MU 7K4 QXP". All of
// them become the one canonical form "MU-7K4QXP" (the body is always 6
// characters, so a leading "MU" on an 8-character input can only be the prefix).
function normaliseCode(raw) {
  const compact = String(raw ?? '').replace(/[\s-]/g, '').toUpperCase();
  if (compact.length === 6) return `MU-${compact}`;
  if (compact.length === 8 && compact.startsWith('MU')) return `MU-${compact.slice(2)}`;
  return compact; // anything else fails isValidCode
}
function isValidCode(raw) {
  return CODE_RE.test(normaliseCode(raw));
}

// --- staff ---------------------------------------------------------------------
function normaliseEmployeeId(raw) {
  return String(raw ?? '').trim().toUpperCase();
}
function isValidEmployeeId(raw) {
  return EMPLOYEE_ID_RE.test(normaliseEmployeeId(raw));
}

module.exports = {
  isValidName,
  isValidStudentNumber,
  normaliseEmail,
  isValidEmail,
  maskEmail,
  normalisePhone,
  isValidPhone,
  normaliseNrc,
  isValidNrc,
  maskNrc,
  isValidDob,
  normaliseCode,
  isValidCode,
  normaliseEmployeeId,
  isValidEmployeeId,
};
