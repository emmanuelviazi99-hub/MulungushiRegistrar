// controllers/request.controller.js
//
// HTTP wrappers for change requests (services/request.service.js). The two
// kinds have identical endpoints, so they are generated from one definition:
//
//   POST /api/group-requests                         student   { groupCode, reason? }
//   GET  /api/group-requests?status=PENDING          lecturer
//   PUT  /api/group-requests/:id/approve|decline     lecturer
//   ...and the same four for /api/number-correction-requests  { newNumber, reason? }
//   GET    /api/my-requests                          student   latest of each kind
//   DELETE /api/my-requests/group|number             student   withdraw an open request
// status may also be ANSWERED: approved + declined, newest first.

const requestService = require('../services/request.service');
const { serializeChangeRequest } = require('../utils/serialize');

function handlersFor(kind) {
  return {
    async create(req, res) {
      const requestId = await requestService.createRequest(kind, req.user.studentId, req.body);
      res.status(201).json({ success: true, requestId, status: 'PENDING' });
    },
    async list(req, res) {
      const rows = await requestService.listRequests(kind, req.query.status || 'PENDING');
      res.json({ success: true, requests: rows.map(serializeChangeRequest) });
    },
    async approve(req, res) {
      await requestService.approve(kind, req.params.id, req.user.accountId);
      res.json({ success: true, status: 'APPROVED' });
    },
    async decline(req, res) {
      await requestService.decline(kind, req.params.id, req.user.accountId);
      res.json({ success: true, status: 'DECLINED' });
    },
  };
}

// GET /api/my-requests (student) — latest request of each kind, or null.
async function listMine(req, res) {
  const latest = await requestService.latestForStudent(req.user.studentId);
  res.json({
    success: true,
    group: serializeChangeRequest(latest.GROUP),
    number: serializeChangeRequest(latest.NUMBER),
  });
}

// DELETE /api/my-requests/group | /api/my-requests/number (student) — withdraw an open request.
async function cancelMine(req, res) {
  const kind = { group: 'GROUP', number: 'NUMBER' }[String(req.params.kind).toLowerCase()];
  if (!kind) {
    res.status(404).json({ success: false, error: 'NOT_FOUND', message: 'Use /my-requests/group or /my-requests/number.' });
    return;
  }
  await requestService.cancelOwn(kind, req.user.studentId);
  res.json({ success: true, status: 'CANCELLED' });
}

const group = handlersFor('GROUP');
const number = handlersFor('NUMBER');

module.exports = {
  listMine,
  cancelMine,
  createGroupRequest: group.create,
  listGroupRequests: group.list,
  approveGroupRequest: group.approve,
  declineGroupRequest: group.decline,
  createNumberCorrectionRequest: number.create,
  listNumberCorrectionRequests: number.list,
  approveNumberCorrectionRequest: number.approve,
  declineNumberCorrectionRequest: number.decline,
};
