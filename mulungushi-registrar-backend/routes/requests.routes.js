// routes/requests.routes.js

const express = require('express');
const router = express.Router();

const requestController = require('../controllers/request.controller');
const asyncHandler = require('../utils/asyncHandler');
const { requireAuth } = require('../middleware/auth');
const { requireRole } = require('../middleware/role');
const {
  validateGroupRequest,
  validateNumberCorrectionRequest,
  handleValidation,
} = require('../middleware/validation');

// --- Group-change requests --------------------------------------------------
router.post(
  '/group-requests',
  requireAuth,
  requireRole('STUDENT'),
  validateGroupRequest,
  handleValidation,
  asyncHandler(requestController.createGroupRequest)
);
router.get(
  '/group-requests',
  requireAuth,
  requireRole('LECTURER'),
  asyncHandler(requestController.listGroupRequests)
);
router.put(
  '/group-requests/:id/approve',
  requireAuth,
  requireRole('LECTURER'),
  asyncHandler(requestController.approveGroupRequest)
);
router.put(
  '/group-requests/:id/decline',
  requireAuth,
  requireRole('LECTURER'),
  asyncHandler(requestController.declineGroupRequest)
);

// --- Number-correction requests ---------------------------------------------
router.post(
  '/number-correction-requests',
  requireAuth,
  requireRole('STUDENT'),
  validateNumberCorrectionRequest,
  handleValidation,
  asyncHandler(requestController.createNumberCorrectionRequest)
);
router.get(
  '/number-correction-requests',
  requireAuth,
  requireRole('LECTURER'),
  asyncHandler(requestController.listNumberCorrectionRequests)
);
router.put(
  '/number-correction-requests/:id/approve',
  requireAuth,
  requireRole('LECTURER'),
  asyncHandler(requestController.approveNumberCorrectionRequest)
);
router.put(
  '/number-correction-requests/:id/decline',
  requireAuth,
  requireRole('LECTURER'),
  asyncHandler(requestController.declineNumberCorrectionRequest)
);

module.exports = router;
