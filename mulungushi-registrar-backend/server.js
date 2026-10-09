// server.js
//
// Wires together middleware, routes and the error handler, then starts
// listening. Keep this file thin — routing logic lives in routes/,
// business logic in controllers/ and services/.

require('dotenv').config();
const express = require('express');
const cors = require('cors');

const authRoutes = require('./routes/auth.routes');
const studentsRoutes = require('./routes/students.routes');
const groupsRoutes = require('./routes/groups.routes');
const requestsRoutes = require('./routes/requests.routes');
const errorHandler = require('./middleware/errorHandler');

const app = express();

app.use(cors()); // tighten this to your app's origin before a real deployment
app.use(express.json());

// Simple liveness check — useful for the Android app's "server reachable?"
// check and for your own sanity when debugging the emulator's networking.
app.get('/health', (req, res) => res.json({ success: true, status: 'ok' }));

app.use('/api/auth', authRoutes);
app.use('/api/students', studentsRoutes);
app.use('/api/groups', groupsRoutes);
app.use('/api', requestsRoutes); // registers /api/group-requests, /api/number-correction-requests

// 404 for anything that didn't match a route above
app.use((req, res) => {
  res.status(404).json({ success: false, error: 'NOT_FOUND', message: 'No such endpoint.' });
});

// Must be LAST: catches everything thrown or passed to next(err) above.
app.use(errorHandler);

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Mulungushi Registrar backend listening on port ${PORT}`); // eslint-disable-line no-console
});

module.exports = app; // exported so tests/ can import it with supertest
