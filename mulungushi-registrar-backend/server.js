// server.js
//
// Wires together middleware, routes and the error handler, then starts
// listening. Keep this file thin — routing logic lives in routes/,
// business logic in controllers/ and services/.

require('dotenv').config();
const express = require('express');
const cors = require('cors');
const { assertConfig } = require('./config/env');

const authRoutes = require('./routes/auth.routes');
const studentsRoutes = require('./routes/students.routes');
const groupsRoutes = require('./routes/groups.routes');
const requestsRoutes = require('./routes/requests.routes');
const errorHandler = require('./middleware/errorHandler');

const app = express();

app.disable('x-powered-by');
app.use(cors()); // tighten this to your app's origin before a real deployment
app.use(express.json({ limit: '50kb' })); // no endpoint needs more

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

// Only listen when run directly (`npm start`). When the test suite imports this
// file it gets the app without a port, so several test files can load it in one
// process without fighting over the port.
if (require.main === module) {
  try {
    assertConfig(); // missing/weak JWT secret, incomplete SMTP settings, console codes in production
  } catch (err) {
    console.error(err.message); // eslint-disable-line no-console
    process.exit(1);
  }
  const { activeChannels } = require('./services/delivery.service');
  const PORT = process.env.PORT || 3000;
  app.listen(PORT, () => {
    /* eslint-disable no-console */
    console.log(`Mulungushi Registrar backend listening on port ${PORT}`);
    const channels = activeChannels();
    console.log(
      `Verification codes are sent by: ${channels.join(' + ')}` +
        (channels.includes('email') ? ` (SMTP ${process.env.SMTP_HOST}:${process.env.SMTP_PORT || 587})` : '')
    );
    /* eslint-enable no-console */
  });
}

module.exports = app; // exported so tests/ can import it with supertest
