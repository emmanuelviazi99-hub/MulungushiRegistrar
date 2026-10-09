// routes/students.routes.js

const express = require('express');
const router = express.Router();

const studentController = require('../controllers/student.controller');
const groupController = require('../controllers/group.controller');
const mutationController = require('../controllers/mutation.controller');
const asyncHandler = require('../utils/asyncHandler');
const { requireAuth } = require('../middleware/auth');
const { requireRole } = require('../middleware/role');
const {
  validateCreateStudent,
  validateUpdateStudent,
  validateAssignGroup,
  validateStudentListQuery,
  handleValidation,
} = require('../middleware/validation');

// --- Student's own profile (role: STUDENT) ---------------------------------
// IMPORTANT: these come before "/:id" below so "/me" is never swallowed by
// the ":id" param route.
router.get('/me', requireAuth, requireRole('STUDENT'), asyncHandler(studentController.getMe));
router.put(
  '/me',
  requireAuth,
  requireRole('STUDENT'),
  validateUpdateStudent,
  handleValidation,
  asyncHandler(studentController.updateMe)
);

router.post('/mutate', requireAuth, asyncHandler(mutationController.mutate));

// --- Lecturer student management --------------------------------------------
router.get(
  '/',
  requireAuth,
  requireRole('LECTURER'),
  validateStudentListQuery,
  handleValidation,
  asyncHandler(studentController.list)
);
router.post(
  '/',
  requireAuth,
  requireRole('LECTURER'),
  validateCreateStudent,
  handleValidation,
  asyncHandler(studentController.create)
);
router.get('/:id', requireAuth, requireRole('LECTURER'), asyncHandler(studentController.getOne));
router.put(
  '/:id',
  requireAuth,
  requireRole('LECTURER'),
  validateUpdateStudent,
  handleValidation,
  asyncHandler(studentController.update)
);
router.delete('/:id', requireAuth, requireRole('LECTURER'), asyncHandler(studentController.remove));

router.post(
  '/:id/group',
  requireAuth,
  requireRole('LECTURER'),
  validateAssignGroup,
  handleValidation,
  asyncHandler(groupController.assignGroup)
);

module.exports = router;
