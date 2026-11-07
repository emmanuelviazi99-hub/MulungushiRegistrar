// controllers/classlist.controller.js
//
// Lecturer only (the routes check the role):
//   POST /api/students/import    { fileName, fileBase64, apply }  -> preview (apply false) or save
//   GET  /api/students/export                                     -> the roster as an .xlsx file
//   POST /api/groups             { capacity? }                    -> the next group (G15 after G14)

const ApiError = require('../utils/ApiError');
const classList = require('../services/classlist.service');

async function importClassList(req, res) {
  const { fileName = 'class-list.xlsx', fileBase64, apply = false } = req.body || {};
  if (typeof fileBase64 !== 'string' || fileBase64.length === 0) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'fileBase64: send the spreadsheet as base64');
  }
  const buffer = Buffer.from(fileBase64, 'base64');
  const result = await classList.importClassList({ buffer, fileName: String(fileName), apply: apply === true });
  res.status(result.applied ? 201 : 200).json({ success: true, ...result });
}

async function exportClassList(req, res) {
  const { buffer, fileName } = await classList.exportClassList();
  res.setHeader('Content-Type', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  res.setHeader('Content-Disposition', `attachment; filename="${fileName}"`);
  res.setHeader('Cache-Control', 'no-store');
  res.send(buffer);
}

async function addGroup(req, res) {
  const capacity = req.body && req.body.capacity !== undefined ? req.body.capacity : undefined;
  const group = await classList.addGroup({ capacity });
  res.status(201).json({ success: true, group });
}

module.exports = { importClassList, exportClassList, addGroup };
