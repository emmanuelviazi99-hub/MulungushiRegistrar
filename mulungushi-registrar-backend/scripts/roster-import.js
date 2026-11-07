// scripts/roster-import.js
//
// Imports the lecturer's class list from the command line (the same rules as the
// app's "Import class list" button). Shows the preview; add --apply to save.
//
//   npm run roster:import -- "C:\path\to\Group List.xlsx"            (preview only)
//   npm run roster:import -- "C:\path\to\Group List.xlsx" --apply    (save)

require('dotenv').config();
const fs = require('fs');
const path = require('path');
const classList = require('../services/classlist.service');
const pool = require('../config/database');

async function main() {
  const args = process.argv.slice(2);
  const file = args.find((a) => !a.startsWith('--'));
  const apply = args.includes('--apply');
  if (!file) {
    console.log('Usage: npm run roster:import -- <file.xlsx|file.csv> [--apply]');
    process.exitCode = 1;
    return;
  }
  const buffer = fs.readFileSync(file);
  const r = await classList.importClassList({ buffer, fileName: path.basename(file), apply });

  console.log(`\n${r.applied ? 'IMPORTED' : 'PREVIEW (nothing saved yet)'}: ${path.basename(file)}`);
  console.log(`  Students in the file: ${r.studentsInFile}`);
  console.log(`  ${r.applied ? 'Added' : 'Will add'}: ${r.toAdd}   Already on the roster (left as they are): ${r.alreadyOnRoster}   Unassigned: ${r.unassigned}`);
  console.log(`  Programmes: ${Object.entries(r.programmes).map(([k, n]) => `${k} ${n}`).join(', ') || '-'}`);
  console.log('  Groups:');
  for (const g of r.groups) {
    const notes = [];
    if (!g.exists) notes.push('new');
    if (g.reopened) notes.push('reopened');
    if (g.capacityRaisedFrom !== null) notes.push(`capacity ${g.capacityRaisedFrom} -> ${g.capacity}`);
    else if (g.overUsualSize) notes.push(`capacity ${g.capacity} (bigger than the usual ${r.usualGroupSize})`);
    console.log(`    ${g.groupCode}: ${g.willHave} students${notes.length ? `  (${notes.join(', ')})` : ''}`);
  }
  if (r.problems.length) {
    console.log(`  Rows not imported or to check (${r.problems.length}):`);
    for (const p of r.problems) console.log(`    - ${p.row}: ${p.message}`);
  }
  if (r.blankRowsSkipped) console.log(`  Empty numbered rows skipped: ${r.blankRowsSkipped}`);
  if (!r.applied) console.log('\nTo save it, run the same command with --apply at the end.');
}

main()
  .catch((err) => {
    console.error(`Import FAILED: ${err.message}`);
    process.exitCode = 1;
  })
  .finally(() => pool.end());
