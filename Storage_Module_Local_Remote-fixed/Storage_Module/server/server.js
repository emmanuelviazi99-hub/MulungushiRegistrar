// Zero-dependency test server:  node server.js
// Env: PORT (default 3000), SYNC_KEY (default "change-me"), DATA_FILE (default ./students-data.json)
// Same contract as the backend: POST /api/students/sync { students } -> { success, students, rejected }
const http = require('http');
const { createStore, validate } = require('./syncCore');

const PORT = process.env.PORT || 3000;
const KEY = process.env.SYNC_KEY || 'change-me';
const store = createStore(process.env.DATA_FILE || './students-data.json');

function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

http.createServer((req, res) => {
  const path = req.url.split('?')[0];
  if (path === '/api/health' || path === '/health') return send(res, 200, { success: true, ok: true });
  if (req.headers['x-sync-key'] !== KEY) return send(res, 401, { success: false, error: 'BAD_SYNC_KEY', message: 'Missing or wrong X-Sync-Key header.' });
  if (req.method === 'GET' && (path === '/api/students' || path === '/api/students/sync')) return send(res, 200, { success: true, students: store.list() });
  if (req.method === 'POST' && path === '/api/students/sync') {
    let body = '';
    req.on('data', (c) => { body += c; if (body.length > 2e6) req.destroy(); });
    req.on('end', () => {
      let data;
      try { data = JSON.parse(body); } catch (e) { return send(res, 400, { success: false, error: 'INVALID_JSON', message: 'The request body is not valid JSON.' }); }
      const problem = validate(data && data.students);
      if (problem) return send(res, 400, { success: false, error: 'VALIDATION_ERROR', message: problem });
      const { students, rejected } = store.sync(data.students);
      send(res, 200, { success: true, students, rejected });
    });
    return;
  }
  send(res, 404, { success: false, error: 'NOT_FOUND', message: 'No such endpoint.' });
}).listen(PORT, '0.0.0.0', () => console.log('Sync server on port ' + PORT));
