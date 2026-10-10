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

const PROGRAMME_CODES = ['CS', 'IT', 'DS'];
const GROUP_CODES = ['G01', 'G02', 'G03', 'G04'];

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

const nameRule = body('fullName')
  .trim()
  .isLength({ min: 2, max: 100 })
  .withMessage('must be 2-100 characters')
  // Letters (incl. accented), spaces, apostrophes, hyphens — ordinary name punctuation.
  .matches(/^[\p{L}][\p{L}\s'.-]*$/u)
  .withMessage('contains characters that are not allowed in a name');

const studentNumberRule = body('studentNumber')
  .trim()
  .matches(/^\d{9}$/)
  .withMessage('must be exactly 9 digits, no spaces');

const programmeRule = body('programmeCode')
  .trim()
  .toUpperCase()
  .isIn(PROGRAMME_CODES)
  .withMessage(`must be one of ${PROGRAMME_CODES.join(', ')}`);

const groupRuleOptional = body('groupCode')
  .optional({ nullable: true })
  .trim()
  .toUpperCase()
  .isIn(GROUP_CODES)
  .withMessage(`must be one of ${GROUP_CODES.join(', ')} or omitted for Unassigned`);

// --- Route-specific chains -------------------------------------------------

const validateCreateStudent = [nameRule, studentNumberRule, programmeRule, groupRuleOptional];

const validateUpdateStudent = [
  nameRule.optional(),
  programmeRule.optional(),
  body('version').optional().isInt({ min: 1 }).withMessage('must be a positive integer'),
];

const validateAssignGroup = [
  body('groupCode')
    .trim()
    .toUpperCase()
    .isIn(GROUP_CODES)
    .withMessage(`must be one of ${GROUP_CODES.join(', ')}`),
  body('operationId').optional().isUUID().withMessage('must be a UUID'),
];

const validateVerifyClaim = [
  studentNumberRule,
  body('claimCode').trim().notEmpty().withMessage('is required'),
];

const validateRegister = [
  studentNumberRule,
  body('claimCode').trim().notEmpty().withMessage('is required'),
  body('username').trim().isLength({ min: 3, max: 60 }).withMessage('must be 3-60 characters'),
  body('password').isLength({ min: 8 }).withMessage('must be at least 8 characters'),
];

const validateLogin = [
  body('username').trim().notEmpty().withMessage('is required'),
  body('password').notEmpty().withMessage('is required'),
];

const validateGroupRequest = [
  groupRuleOptional.not().isEmpty().withMessage('is required').bail(),
  body('reason').optional().trim().isLength({ max: 255 }),
];

const validateNumberCorrectionRequest = [
  body('newNumber')
    .trim()
    .matches(/^\d{9}$/)
    .withMessage('must be exactly 9 digits, no spaces'),
  body('reason').optional().trim().isLength({ max: 255 }),
];

const validateStudentListQuery = [
  query('programme').optional().trim().toUpperCase().isIn(PROGRAMME_CODES),
  query('group').optional().trim().toUpperCase().isIn([...GROUP_CODES, 'UNASSIGNED']),
  query('page').optional().isInt({ min: 1 }),
  query('limit').optional().isInt({ min: 1, max: 100 }),
];

module.exports = {
  PROGRAMME_CODES,
  GROUP_CODES,
  handleValidation,
  validateCreateStudent,
  validateUpdateStudent,
  validateAssignGroup,
  validateVerifyClaim,
  validateRegister,
  validateLogin,
  validateGroupRequest,
  validateNumberCorrectionRequest,
  validateStudentListQuery,
};
