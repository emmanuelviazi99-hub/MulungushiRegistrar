// routes/auth.routes.js

const express = require('express');
const router = express.Router();

const authController = require('../controllers/auth.controller');
const asyncHandler = require('../utils/asyncHandler');
const { requireAuth } = require('../middleware/auth');
const {
  validateVerifyClaim,
  validateRegister,
  validateLogin,
  handleValidation,
} = require('../middleware/validation');

router.post('/verify-claim', validateVerifyClaim, handleValidation, asyncHandler(authController.verifyClaim));
router.post('/register', validateRegister, handleValidation, asyncHandler(authController.register));
router.post('/login', validateLogin, handleValidation, asyncHandler(authController.login));
router.post('/logout', requireAuth, asyncHandler(authController.logout));

module.exports = router;
