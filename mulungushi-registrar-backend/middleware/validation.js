// middleware/validation.js
//
// Server-side validation using express-validator. The Android app should
// validate too, for a fast, friendly UI — but the rules here are the ones
// that actually matter, because a request can always be sent from
// somewhere other than the app (curl, Postman, a modified APK).
//
// Each exported array is a list of middleware you plug into a route:
//   router.post('/students', validateCreateStudent, handleValidation, ctrl.create)

const { body, query, validationResult } = require('express-validator');
const ApiError = require('../utils/ApiError');
const v = require('../utils/validators');

// Turns express-validator's error array into our standard ApiError shape.
// Put this immediately after any validate*() chain.
function handleValidation(req, res, next) {
  const result = validationResult(req);
  if (!result.isEmpty()) {
    const first = result.array()[0];
    throw new ApiError(422, 'VALIDATION_ERROR', `${first.path}: ${first.msg}`);
  }
  next();
}

// --- Reusable field rules -------------------------------------------------
//
// Each rule is a FUNCTION that builds a fresh chain. express-validator chains
// are mutable: calling .optional() on a shared chain object changes it for
// every route that uses it (an earlier version made fullName and programmeCode
// silently optional on "create student" that way).
//
// Programme and group codes are only checked for SHAPE here. Whether a code
// exists is answered by the programmes / lab_groups tables (400 INVALID_PROGRAMME
// / INVALID_GROUP), so adding a programme or a group is a database insert, not a
// code change — the lists are stored once, in the database.

const nameRule = () =>
  body('fullName')
    .trim()
    .isLength({ min: 2, max: 100 })
    .withMessage('must be 2-100 characters')
    // Letters (incl. accented), spaces, apostrophes, hyphens — ordinary name punctuation.
    .matches(/^[\p{L}][\p{L}\s'.-]*$/u)
    .withMessage('contains characters that are not allowed in a name');

const studentNumberRule = (field = 'studentNumber') =>
  body(field).trim().matches(/^\d{9}$/).withMessage('must be exactly 9 digits, no spaces');

const programmeRule = () =>
  body('programmeCode').trim().toUpperCase().matches(/^[A-Z]{2,5}$/).withMessage('must be a programme code such as CS');

const groupRule = () =>
  body('groupCode')
    .trim()
    .toUpperCase()
    .matches(/^[A-Z0-9]{1,10}$/)
    .withMessage('must be a lab group code such as G01, or UNASSIGNED');

const emailRule = (field = 'email') =>
  body(field)
    .customSanitizer(v.normaliseEmail)
    .custom(v.isValidEmail)
    .withMessage('must be a valid email address');

// Zambian mobile number; accepts 0977123456, 260977123456, +260 977 123 456 ... and
// stores it as +260977123456 (same normalisation as the Android app).
const phoneRule = (field = 'phone') =>
  body(field)
    .customSanitizer(v.normalisePhone)
    .custom(v.isValidPhone)
    .withMessage('must be a Zambian mobile number like +260977123456');

const nrcRule = () =>
  body('nrc').customSanitizer(v.normaliseNrc).custom(v.isValidNrc).withMessage('must look like 123456/10/1');

const dobRule = () =>
  body('dateOfBirth')
    .custom((value) => v.isValidDob(value))
    .withMessage('must be a real date in the past, formatted YYYY-MM-DD');

const codeRule = () =>
  body('code')
    .customSanitizer(v.normaliseCode)
    .custom(v.isValidCode)
    .withMessage('is not a valid code (it looks like MU-7K4Q)');

// bcrypt only reads the first 72 bytes, so a longer password would be silently
// truncated — reject it instead.
const passwordRule = (field = 'password') =>
  body(field).isString().withMessage('is required').bail().isLength({ min: 8, max: 72 }).withMessage('must be 8-72 characters');

const reasonRule = () => body('reason').optional({ nullable: true }).trim().isLength({ max: 255 });

// --- Route-specific chains -------------------------------------------------

const validateCreateStudent = [
  nameRule(),
  studentNumberRule(),
  nrcRule(),
  dobRule(),
  programmeRule(),
  groupRule().optional({ nullable: true }),
];

// nrc / dateOfBirth are accepted here for LECTURER corrections only: a student's
// own PUT /students/me ignores them (see studentService.updateStudent).
const validateUpdateStudent = [
  nameRule().optional(),
  programmeRule().optional(),
  nrcRule().optional(),
  dobRule().optional(),
  body('version').optional().isInt({ min: 1 }).withMessage('must be a positive integer'),
];

const validateAssignGroup = [groupRule(), body('operationId').optional().isUUID().withMessage('must be a UUID')];

// --- Sign-up, password reset and login -----------------------------------------

// Step 1 (student): prove you are on the roster. studentNumber + nrc + dateOfBirth
// must all match the row a lecturer created.
const validateStudentRegisterRequest = [nameRule(), studentNumberRule(), nrcRule(), dobRule(), emailRule(), phoneRule()];

// Step 1 (lecturer): email + employee ID must be on the approved staff list.
const validateStaffRegisterRequest = [
  nameRule(),
  emailRule(),
  body('employeeId')
    .customSanitizer(v.normaliseEmployeeId)
    .custom(v.isValidEmployeeId)
    .withMessage('must be your employee ID, e.g. EMP-1001'),
  phoneRule().optional({ checkFalsy: true }),
];

// Step 2 (everyone): enter the code that was sent.
const validateVerifyCode = [emailRule(), codeRule()];

// Step 3
const validateStudentComplete = [emailRule(), passwordRule(), programmeRule().optional()];
const validateStaffComplete = [emailRule(), passwordRule()];

const validatePasswordResetRequest = [emailRule()];
const validatePasswordReset = [emailRule(), codeRule(), passwordRule('newPassword')];

// loginKey is a 9-digit student number or an email address.
const validateLogin = [
  body('loginKey').isString().withMessage('is required').bail().trim().notEmpty().withMessage('is required'),
  body('password').isString().withMessage('is required').bail().notEmpty().withMessage('is required'),
];

const validateGroupRequest = [groupRule(), reasonRule()];
const validateNumberCorrectionRequest = [studentNumberRule('newNumber'), reasonRule()];

const validateStudentListQuery = [
  query('programme').optional().trim().toUpperCase().matches(/^[A-Z]{2,5}$/),
  query('group').optional().trim().toUpperCase().matches(/^[A-Z0-9]{1,10}$/),
  query('page').optional().isInt({ min: 1 }),
  query('limit').optional().isInt({ min: 1, max: 100 }),
];

module.exports = {
  handleValidation,
  validateCreateStudent,
  validateUpdateStudent,
  validateAssignGroup,
  validateStudentRegisterRequest,
  validateStaffRegisterRequest,
  validateVerifyCode,
  validateStudentComplete,
  validateStaffComplete,
  validatePasswordResetRequest,
  validatePasswordReset,
  validateLogin,
  validateGroupRequest,
  validateNumberCorrectionRequest,
  validateStudentListQuery,
};
