// routes/groups.routes.js

const express = require('express');
const router = express.Router();

const groupController = require('../controllers/group.controller');
const asyncHandler = require('../utils/asyncHandler');
const { requireAuth } = require('../middleware/auth');
const { requireRole } = require('../middleware/role');

// Both roles can see totals: a student sees "how full is my group" on
// their profile screen; a lecturer sees totals on the dashboard.
router.get('/', requireAuth, requireRole('STUDENT', 'LECTURER'), asyncHandler(groupController.listTotals));

module.exports = router;
