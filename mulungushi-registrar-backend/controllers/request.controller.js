// controllers/request.controller.js
//
// HTTP wrappers for change requests (services/request.service.js). The two
// kinds have identical endpoints, so they are generated from one definition:
//
//   POST /api/group-requests                         student   { groupCode, reason? }
//   GET  /api/group-requests?status=PENDING          lecturer
//   PUT  /api/group-requests/:id/approve|decline     lecturer
//   ...and the same four for /api/number-correction-requests  { newNumber, reason? }

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

const group = handlersFor('GROUP');
const number = handlersFor('NUMBER');

module.exports = {
  createGroupRequest: group.create,
  listGroupRequests: group.list,
  approveGroupRequest: group.approve,
  declineGroupRequest: group.decline,
  createNumberCorrectionRequest: number.create,
  listNumberCorrectionRequests: number.list,
  approveNumberCorrectionRequest: number.approve,
  declineNumberCorrectionRequest: number.decline,
};
