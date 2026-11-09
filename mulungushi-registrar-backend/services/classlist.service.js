// services/classlist.service.js
//
// The lecturer's class list as a spreadsheet: import it (with a preview first)
// and download the roster as one.
//
// IMPORT. The list the lecturer was given looks like this (one sheet):
//
//        GROUP 1
//     1  Mulemwa   Muchima   202402387  M  BSCS
//     2  Edwin     Kampamba  202406643  M  BSCS
//     ...
//        GROUP12                       <- typed without a space: still a heading
//     ...
//
// so a row with only "Group N" starts group GNN, and a student row is any row
// with a 9-digit student number: the words before it are the name (the row
// number is skipped), the first thing after it that is not M/F/Male/Female is
// the programme. A plain table with a header row ("Student number", "Name",
// "Programme", "Group", ...) works too, and so does a workbook with one sheet
// per group named "Group 1", "Group 2" ... CSV files are read the same way.
//
// The list is taken AS IT IS: nobody is moved, nothing is fixed by guessing.
//   - A student number listed twice: the first row counts, the others are
//     reported (the lecturer fixes them in the app later).
//   - A group bigger than the usual 15 keeps everyone and is reported. Its
//     capacity stays 15: it shows as full (16/16) and takes nobody new; as
//     members leave it comes down to 15/15 like any other group.
//   - A student number already on the roster is left alone (re-importing the
//     same sheet adds nothing), so the lecturer's changes in the app are kept.
//   - A programme that can't be recognised, a bad student number or name:
//     that row is not imported, and reported.
// Imported students have no NRC or date of birth (the sheet has neither) and
// no account; a student who signs up later fills those in.
//
// Nothing is saved until apply = true; the same function makes the preview.

const crypto = require('crypto');
const { Readable } = require('stream');
const ExcelJS = require('exceljs');

const pool = require('../config/database');
const { withTransaction } = require('../utils/transaction');
const ApiError = require('../utils/ApiError');
const v = require('../utils/validators');

const DEFAULT_CAPACITY = Math.max(1, Math.min(255, Number(process.env.GROUP_CAPACITY) || 15));
const MAX_ROWS = 5000;

// ----------------------------------------------------------------- reading

/** A cell's value as plain text ("" for empty). Numbers keep all digits (202402387, not 2.02e8). */
function cellText(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : String(value);
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  if (typeof value === 'object') {
    if (Array.isArray(value.richText)) return value.richText.map((t) => t.text).join('').trim();
    if (value.text !== undefined) return cellText(value.text);
    if (value.result !== undefined) return cellText(value.result);
    return '';
  }
  return String(value).replace(/\s+/g, ' ').trim();
}

/** "GROUP 1", "Group12", "group 03", "G04" -> "G01", "G12", "G03", "G04". Anything else -> null. */
function groupCodeFrom(text) {
  const m = /^\s*(?:group|grp|g)\s*0*(\d{1,3})\s*$/i.exec(String(text ?? ''));
  if (!m) return null;
  const n = Number(m[1]);
  if (n < 1) return null;
  return `G${String(n).padStart(2, '0')}`;
}

const SEX_WORDS = new Set(['M', 'F', 'MALE', 'FEMALE']);

/**
 * The programme as written on the list -> our code. The class list writes the same
 * programme many ways ("BSCS", "BCSC", "BsCS", "Computer Science", "BSc in Computer Science").
 */
function programmeCodeFrom(raw) {
  const t = String(raw ?? '').toLowerCase().replace(/[^a-z]/g, '');
  if (!t) return null;
  if (t.includes('education') || t === 'icted' || t === 'bicted' || t === 'bscicted') return 'ICTED';
  if (t.includes('computerscience') || ['cs', 'bscs', 'bcsc', 'bsccs', 'bcs'].includes(t)) return 'CS';
  if (t.includes('informationtechnology') || ['it', 'bsit', 'bscit', 'bit'].includes(t)) return 'IT';
  if (t.includes('datascience') || ['ds', 'bsds', 'bscds'].includes(t)) return 'DS';
  return null;
}

/** Reads every sheet into plain rows of text. */
async function readWorkbook(buffer, fileName = '') {
  const wb = new ExcelJS.Workbook();
  try {
    if (/\.csv$/i.test(fileName)) {
      await wb.csv.read(Readable.from(buffer));
    } else {
      await wb.xlsx.load(buffer);
    }
  } catch (err) {
    throw new ApiError(422, 'UNREADABLE_FILE', 'That file could not be read. Save it as an Excel (.xlsx) or CSV file and try again.');
  }
  const sheets = [];
  wb.eachSheet((ws) => {
    const rows = [];
    ws.eachRow({ includeEmpty: false }, (row, rowNumber) => {
      const cells = [];
      for (let c = 1; c <= row.cellCount; c++) cells.push(cellText(row.getCell(c).value));
      rows.push({ rowNumber, cells });
    });
    sheets.push({ name: ws.name, rows });
  });
  return sheets;
}

/**
 * Turns the sheets into student rows and problems. Pure: no database.
 * @returns {{ students: Array, problems: Array, blankRows: number }}
 */
function parseSheets(sheets) {
  const students = [];
  const problems = [];
  let blankRows = 0;
  const multiSheet = sheets.length > 1;

  for (const sheet of sheets) {
    let group = groupCodeFrom(sheet.name); // a workbook with one sheet per group
    let header = null; // column positions when the sheet has a header row
    const where = (rowNumber) => (multiSheet ? `${sheet.name} row ${rowNumber}` : `row ${rowNumber}`);

    for (const { rowNumber, cells } of sheet.rows) {
      const filled = cells.map((c, i) => ({ c, i })).filter((x) => x.c !== '');
      if (filled.length === 0) continue;

      // "GROUP 1" on a row of its own starts a group
      if (filled.length <= 2) {
        const code = groupCodeFrom(filled[filled.length - 1].c);
        if (code && filled.every((x) => /^\d{0,3}$/.test(x.c) || groupCodeFrom(x.c))) {
          group = code;
          continue;
        }
      }

      // a header row: remember which column holds what
      const lower = cells.map((c) => c.toLowerCase());
      const numberCol = lower.findIndex((c) => /student\s*(no|number|num|id)|^reg(istration)?\s*(no|number)/.test(c));
      if (numberCol >= 0) {
        header = {
          number: numberCol,
          full: lower.findIndex((c) => /^(full\s*)?names?$/.test(c)),
          first: lower.findIndex((c) => /^(first|given)\s*names?$|^first$/.test(c)),
          last: lower.findIndex((c) => /^(sur\s*name|last\s*name|surname|family\s*name)$/.test(c)),
          programme: lower.findIndex((c) => /^(programme|program|course|degree)/.test(c)),
          group: lower.findIndex((c) => /^(lab\s*)?group/.test(c)),
        };
        continue;
      }

      // a student row: it has a 9-digit student number
      let numberAt = header ? header.number : cells.findIndex((c) => /^\d{9}$/.test(c));
      const studentNumber = numberAt >= 0 ? (cells[numberAt] || '').replace(/\s/g, '') : '';
      if (!/^\d{9}$/.test(studentNumber)) {
        // just a row number with nothing else (an empty slot on the list): skip quietly
        if (filled.every((x) => /^\d{1,3}$/.test(x.c))) {
          blankRows++;
          continue;
        }
        problems.push({ row: where(rowNumber), kind: 'BAD_STUDENT_NUMBER', message: `No 9-digit student number in: ${filled.map((x) => x.c).join(' | ')}` });
        continue;
      }

      let fullName;
      let programmeRaw;
      let rowGroup = group;
      if (header) {
        if (header.full >= 0) fullName = cells[header.full];
        else fullName = [cells[header.first], cells[header.last]].filter(Boolean).join(' ');
        programmeRaw = header.programme >= 0 ? cells[header.programme] : '';
        if (header.group >= 0 && cells[header.group]) {
          const g = groupCodeFrom(cells[header.group]);
          if (g) rowGroup = g;
          else if (/^unassigned$|^none$|^-$/i.test(cells[header.group])) rowGroup = null;
        }
      } else {
        // words before the number make the name (the row number is skipped)
        fullName = cells.slice(0, numberAt).filter((c) => c && !/^\d+$/.test(c)).join(' ');
        // the first thing after the number that is not M/F is the programme; a group code there wins
        const after = cells.slice(numberAt + 1).filter((c) => c !== '');
        for (const c of after) {
          const g = groupCodeFrom(c);
          if (g && /^g|group/i.test(c)) { rowGroup = g; continue; }
          if (SEX_WORDS.has(c.toUpperCase())) continue;
          if (!programmeRaw) programmeRaw = c;
        }
      }
      // curly apostrophes (Wakun’oli) count as plain ones; spaces tidied
      fullName = String(fullName || '').replace(/[\u2018\u2019\u02BC`]/g, "'").replace(/\s+/g, ' ').trim();
      const programmeCode = programmeCodeFrom(programmeRaw);

      const row = { row: where(rowNumber), studentNumber, fullName, programmeRaw: programmeRaw || '', programmeCode, groupCode: rowGroup };
      if (!v.isValidName(fullName)) {
        problems.push({ row: row.row, studentNumber, kind: 'BAD_NAME', message: `The name "${fullName}" can't be used (letters, spaces, apostrophes, hyphens, full stops; 2-100 characters).` });
        continue;
      }
      if (!programmeCode) {
        problems.push({ row: row.row, studentNumber, kind: 'UNKNOWN_PROGRAMME', message: `Programme "${programmeRaw || '(empty)'}" is not one the app knows (CS, IT, DS, ICT with Education).` });
        continue;
      }
      students.push(row);
      if (students.length > MAX_ROWS) throw new ApiError(422, 'TOO_MANY_ROWS', `A class list can have at most ${MAX_ROWS} students.`);
    }
  }
  return { students, problems, blankRows };
}

// ----------------------------------------------------------------- the plan (preview) and saving it

async function buildPlan(conn, parsed, { lock = false } = {}) {
  const { students, problems } = parsed;
  const lockSql = lock ? ' FOR UPDATE' : '';

  // Listed twice in the file: the first row counts
  const firstAt = new Map();
  const unique = [];
  for (const s of students) {
    const first = firstAt.get(s.studentNumber);
    if (first) {
      problems.push({
        row: s.row,
        studentNumber: s.studentNumber,
        kind: 'DUPLICATE_IN_FILE',
        message: `${s.studentNumber} (${s.fullName}) is also on ${first.row}${first.groupCode ? ` in ${first.groupCode}` : ''}; the first one is kept${s.groupCode && s.groupCode !== first.groupCode ? ` (this row says ${s.groupCode})` : ''}.`,
      });
      continue;
    }
    firstAt.set(s.studentNumber, s);
    unique.push(s);
  }

  // Already on the roster (also a removed student: the number stays reserved)
  const existing = new Map();
  for (let i = 0; i < unique.length; i += 500) {
    const chunk = unique.slice(i, i + 500).map((s) => s.studentNumber);
    if (chunk.length === 0) continue;
    const [rows] = await conn.query(
      `SELECT s.student_number, s.active, g.group_code FROM students s
         LEFT JOIN lab_groups g ON g.group_id = s.group_id
        WHERE s.student_number IN (?)${lockSql}`,
      [chunk]
    );
    for (const r of rows) existing.set(r.student_number, r);
  }
  const toAdd = [];
  const alreadyOnRoster = [];
  for (const s of unique) {
    const e = existing.get(s.studentNumber);
    if (e) alreadyOnRoster.push({ row: s.row, studentNumber: s.studentNumber, fullName: s.fullName, currentGroup: e.group_code || null, removed: !e.active });
    else toAdd.push(s);
  }

  // Groups: which exist, which are new, which must grow to keep the list as it is
  const codes = [...new Set(toAdd.map((s) => s.groupCode).filter(Boolean))].sort();
  const groups = [];
  if (codes.length) {
    const [rows] = await conn.query(
      `SELECT g.group_id, g.group_code, g.capacity, g.active,
              (SELECT COUNT(*) FROM students s WHERE s.group_id = g.group_id AND s.active = 1) AS members
         FROM lab_groups g WHERE g.group_code IN (?)${lockSql}`,
      [codes]
    );
    const byCode = new Map(rows.map((r) => [r.group_code, r]));
    for (const code of codes) {
      const adding = toAdd.filter((s) => s.groupCode === code).length;
      const g = byCode.get(code);
      const now = g ? Number(g.members) : 0;
      const limit = g ? Number(g.capacity) : DEFAULT_CAPACITY;
      const willHave = now + adding;
      // shown like the dashboard shows it: a group over its limit reads "16 of 16" (full)
      const capacity = Math.max(limit, willHave);
      groups.push({
        groupCode: code,
        exists: !!g,
        membersNow: now,
        adding,
        willHave,
        capacity,
        overUsualSize: willHave > limit,
        reopened: !!g && !g.active,
      });
    }
  }

  const programmes = {};
  for (const s of toAdd) programmes[s.programmeCode] = (programmes[s.programmeCode] || 0) + 1;

  return {
    studentsInFile: students.length,
    toAdd,
    alreadyOnRoster,
    groups,
    programmes,
    unassigned: toAdd.filter((s) => !s.groupCode).length,
    problems,
    blankRows: parsed.blankRows,
  };
}

/** What the API answers: the plan without the full list of rows to add. */
function summary(plan, applied) {
  return {
    applied,
    studentsInFile: plan.studentsInFile,
    toAdd: plan.toAdd.length,
    added: applied ? plan.toAdd.length : 0,
    alreadyOnRoster: plan.alreadyOnRoster.length,
    alreadyOnRosterRows: plan.alreadyOnRoster.slice(0, 50),
    unassigned: plan.unassigned,
    groups: plan.groups,
    programmes: plan.programmes,
    problems: plan.problems,
    blankRowsSkipped: plan.blankRows,
    usualGroupSize: DEFAULT_CAPACITY,
  };
}

/**
 * Reads a class list and either previews it (apply false) or saves it (apply true).
 * Saving re-reads the roster under locks, so the answer is exactly what was saved.
 */
async function importClassList({ buffer, fileName, apply = false }) {
  if (!buffer || buffer.length === 0) throw new ApiError(422, 'VALIDATION_ERROR', 'file: the file is empty');
  const parsed = parseSheets(await readWorkbook(buffer, fileName));
  if (parsed.students.length === 0 && parsed.problems.length === 0) {
    throw new ApiError(422, 'NO_STUDENTS_FOUND', 'No students were found. Each student needs a 9-digit student number on their row.');
  }
  if (!apply) {
    const plan = await buildPlan(pool, parsed);
    return summary(plan, false);
  }
  return withTransaction(async (conn) => {
    const plan = await buildPlan(conn, parsed, { lock: true });
    // groups first: create new ones (the usual size) and reopen closed ones. A group whose
    // list is longer keeps everyone, but its limit is not raised (see the top of this file).
    for (const g of plan.groups) {
      if (!g.exists) {
        await conn.query('INSERT INTO lab_groups (group_code, capacity, active) VALUES (?, ?, 1)', [g.groupCode, DEFAULT_CAPACITY]);
      } else if (g.reopened) {
        await conn.query('UPDATE lab_groups SET active = 1 WHERE group_code = ?', [g.groupCode]);
      }
    }
    if (plan.toAdd.length) {
      const [progRows] = await conn.query('SELECT programme_id, code FROM programmes');
      const programmeId = new Map(progRows.map((r) => [r.code, r.programme_id]));
      const [groupRows] = await conn.query('SELECT group_id, group_code FROM lab_groups WHERE group_code IN (?)', [
        plan.groups.length ? plan.groups.map((g) => g.groupCode) : ['-'],
      ]);
      const groupId = new Map(groupRows.map((r) => [r.group_code, r.group_id]));
      for (let i = 0; i < plan.toAdd.length; i += 200) {
        const values = plan.toAdd.slice(i, i + 200).map((s) => {
          const pid = programmeId.get(s.programmeCode);
          if (!pid) throw new ApiError(500, 'PROGRAMME_MISSING', `Programme ${s.programmeCode} is not in the database: run npm run db:migrate.`);
          return [crypto.randomUUID(), s.studentNumber, null, null, s.fullName, pid, s.groupCode ? groupId.get(s.groupCode) : null];
        });
        await conn.query(
          'INSERT INTO students (student_id, student_number, nrc, date_of_birth, full_name, programme_id, group_id) VALUES ?',
          [values]
        );
      }
    }
    return summary(plan, true);
  });
}

// ----------------------------------------------------------------- download

const HEADER = ['No.', 'Student number', 'Full name', 'Programme', 'Group', 'Signed up'];

function styleSheet(ws, widths) {
  ws.columns = widths.map((w) => ({ width: w }));
  const head = ws.getRow(1);
  head.font = { bold: true, color: { argb: 'FFFFFFFF' } };
  head.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF1F3A5F' } };
  head.alignment = { vertical: 'middle' };
  ws.views = [{ state: 'frozen', ySplit: 1 }];
}

/**
 * The roster as an .xlsx file: a "Groups" summary, "All students", then one sheet
 * per group (and "Unassigned" when anyone is). Returns { buffer, fileName }.
 */
async function exportClassList() {
  const [students] = await pool.query(
    `SELECT s.student_number, s.full_name, p.code AS programme_code, p.name AS programme_name,
            g.group_code, (a.account_id IS NOT NULL) AS signed_up
       FROM students s
       JOIN programmes p ON p.programme_id = s.programme_id
       LEFT JOIN lab_groups g ON g.group_id = s.group_id
       LEFT JOIN accounts a ON a.student_id = s.student_id
      WHERE s.active = 1
      ORDER BY (g.group_code IS NULL), g.group_code, s.full_name`
  );
  const [groups] = await pool.query(
    `SELECT g.group_code, GREATEST(g.capacity, COUNT(s.student_id)) AS capacity, COUNT(s.student_id) AS members
       FROM lab_groups g
       LEFT JOIN students s ON s.group_id = g.group_id AND s.active = 1
      WHERE g.active = 1
      GROUP BY g.group_id ORDER BY g.group_code`
  );

  const wb = new ExcelJS.Workbook();
  wb.creator = 'Mulungushi Registrar';
  wb.created = new Date();

  const summarySheet = wb.addWorksheet('Groups');
  summarySheet.addRow(['Group', 'Members', 'Capacity', 'Places left']);
  for (const g of groups) {
    summarySheet.addRow([g.group_code, Number(g.members), Number(g.capacity), Math.max(0, g.capacity - g.members)]);
  }
  const unassignedCount = students.filter((s) => !s.group_code).length;
  if (unassignedCount) summarySheet.addRow(['Unassigned', unassignedCount, null, null]);
  summarySheet.addRow([]);
  summarySheet.addRow(['Total students', students.length]);
  styleSheet(summarySheet, [16, 10, 10, 12]);

  const rowOf = (s, i) => [i + 1, s.student_number, s.full_name, s.programme_name, s.group_code || 'Unassigned', s.signed_up ? 'Yes' : 'No'];
  const all = wb.addWorksheet('All students');
  all.addRow(HEADER);
  students.forEach((s, i) => all.addRow(rowOf(s, i)));
  all.autoFilter = { from: 'A1', to: 'F1' };
  styleSheet(all, [6, 16, 32, 24, 12, 10]);

  const sheetsFor = groups.map((g) => g.group_code);
  if (unassignedCount) sheetsFor.push(null);
  for (const code of sheetsFor) {
    const ws = wb.addWorksheet(code || 'Unassigned');
    ws.addRow(HEADER);
    students.filter((s) => (s.group_code || null) === code).forEach((s, i) => ws.addRow(rowOf(s, i)));
    styleSheet(ws, [6, 16, 32, 24, 12, 10]);
  }

  const buffer = Buffer.from(await wb.xlsx.writeBuffer());
  const day = new Date().toISOString().slice(0, 10);
  return { buffer, fileName: `Mulungushi-Registrar-groups-${day}.xlsx` };
}

// ----------------------------------------------------------------- add a group

/** The next group after the highest number (G14 -> G15), with the usual capacity unless told otherwise. */
async function addGroup({ capacity } = {}) {
  const cap = capacity === undefined || capacity === null ? DEFAULT_CAPACITY : Number(capacity);
  if (!Number.isInteger(cap) || cap < 1 || cap > 255) {
    throw new ApiError(422, 'VALIDATION_ERROR', 'capacity: must be a whole number from 1 to 255');
  }
  for (let attempt = 0; attempt < 3; attempt++) {
    const [rows] = await pool.query("SELECT group_code FROM lab_groups WHERE group_code REGEXP '^G[0-9]+$'");
    const highest = rows.reduce((max, r) => Math.max(max, Number(r.group_code.slice(1))), 0);
    const code = `G${String(highest + 1).padStart(2, '0')}`;
    try {
      await pool.query('INSERT INTO lab_groups (group_code, capacity, active) VALUES (?, ?, 1)', [code, cap]);
      return { groupCode: code, capacity: cap, members: 0 };
    } catch (err) {
      if (err && err.code === 'ER_DUP_ENTRY') continue; // two lecturers at once: take the next number
      throw err;
    }
  }
  throw new ApiError(409, 'TRY_AGAIN', 'Another group was added at the same moment. Try again.');
}

module.exports = {
  importClassList,
  exportClassList,
  addGroup,
  // for tests
  parseSheets,
  readWorkbook,
  groupCodeFrom,
  programmeCodeFrom,
  DEFAULT_CAPACITY,
};
