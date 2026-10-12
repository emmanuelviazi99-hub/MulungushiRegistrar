// Stand-alone router with the same contract as the backend's routes/sync.routes.js.
// NOTE: the Mulungushi Registrar backend already includes remote storage (MySQL table
// student_sync_records, mounted at /api/students/sync) - you do NOT need this file there.
// It is only for some other Express app:
//   const students = require('./studentsRouter');
//   app.use('/api', students('./students-data.json'));   // before any express.json() with a small limit
const express = require('express');
const { createStore, validate } = require('./syncCore');

module.exports = function studentsRouter(file) {
  const store = createStore(file);
  const router = express.Router();
  const KEY = process.env.SYNC_KEY || 'change-me';

  router.use('/students', (req, res, next) => {
    if (req.headers['x-sync-key'] !== KEY) return res.status(401).json({ success: false, error: 'BAD_SYNC_KEY' });
    next();
  });
  router.get(['/students', '/students/sync'], (req, res) => res.json({ success: true, students: store.list() }));
  router.post('/students/sync', express.json({ limit: '2mb' }), (req, res) => {
    const problem = validate(req.body && req.body.students);
    if (problem) return res.status(400).json({ success: false, error: 'VALIDATION_ERROR', message: problem });
    const { students, rejected } = store.sync(req.body.students);
    res.json({ success: true, students, rejected });
  });
  return router;
};
