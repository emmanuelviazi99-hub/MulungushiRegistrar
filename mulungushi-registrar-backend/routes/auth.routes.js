// routes/auth.routes.js
//
//   Student sign-up    POST /register/request  ->  /register/verify  ->  /register/complete
//   Lecturer sign-up   POST /staff/request     ->  /staff/verify     ->  /staff/complete
//   Password reset     POST /password/request  ->  /password/verify  ->  /password/reset
//   Sign in / out      POST /login, /logout

const express = require('express');
const router = express.Router();

const c = require('../controllers/auth.controller');
const asyncHandler = require('../utils/asyncHandler');
const { requireAuth } = require('../middleware/auth');
const { codeRequestLimiter, codeVerifyLimiter, loginLimiter } = require('../middleware/rateLimit');
const v = require('../middleware/validation');

const post = (path, limiter, chain, handler) =>
  router.post(path, limiter, ...chain, v.handleValidation, asyncHandler(handler));

// Students
post('/register/request', codeRequestLimiter, v.validateStudentRegisterRequest, c.studentRegisterRequest);
post('/register/verify', codeVerifyLimiter, v.validateVerifyCode, c.studentRegisterVerify);
post('/register/complete', codeVerifyLimiter, v.validateStudentComplete, c.studentRegisterComplete);

// Lecturers
post('/staff/request', codeRequestLimiter, v.validateStaffRegisterRequest, c.staffRegisterRequest);
post('/staff/verify', codeVerifyLimiter, v.validateVerifyCode, c.staffRegisterVerify);
post('/staff/complete', codeVerifyLimiter, v.validateStaffComplete, c.staffRegisterComplete);

// Forgotten password (either role)
post('/password/request', codeRequestLimiter, v.validatePasswordResetRequest, c.passwordResetRequest);
post('/password/verify', codeVerifyLimiter, v.validateVerifyCode, c.passwordResetVerify);
post('/password/reset', codeVerifyLimiter, v.validatePasswordReset, c.passwordReset);

post('/login', loginLimiter, v.validateLogin, c.login);
router.post('/logout', requireAuth, asyncHandler(c.logout));

module.exports = router;
