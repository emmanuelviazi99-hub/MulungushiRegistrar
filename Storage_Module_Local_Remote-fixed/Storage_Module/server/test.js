// node test.js  - starts the server on a spare port and checks the sync rules end to end.
const { spawn } = require('child_process');
const fs = require('fs');
const PORT = 3999, F = './test-data.json';
if (fs.existsSync(F)) fs.unlinkSync(F);
const start = () => spawn('node', ['server.js'], { env: { ...process.env, PORT, DATA_FILE: F, SYNC_KEY: 'k' } });
let srv = start();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const post = (body, key = 'k') => fetch(`http://127.0.0.1:${PORT}/api/students/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Sync-Key': key }, body: JSON.stringify(body) });
const rec = (id, number, o = {}) => ({ id, firstName: 'Natasha', lastName: 'Zulu', number, programme: 'CS', email: 'n@example.com', updatedAt: 100, ...o });
let fails = 0;
const check = (name, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) fails++; };
(async () => {
  await sleep(700);
  try {
    check('rejects wrong key', (await post({ students: [] }, 'nope')).status === 401);
    check('rejects a non-array body', (await post({ students: 'x' })).status === 400);
    let r = await (await post({ students: [rec('a', '202301001', { firstName: 'Old' })] })).json();
    check('stores new student with first and last name', r.students.length === 1 && r.students[0].firstName === 'Old' && r.students[0].lastName === 'Zulu');
    r = await (await post({ students: [rec('a', '202301001', { firstName: 'New', updatedAt: 200 })] })).json();
    check('newer update wins', r.students[0].firstName === 'New');
    r = await (await post({ students: [rec('a', '202301001', { firstName: 'Stale', updatedAt: 150 })] })).json();
    check('older update is ignored and server copy returned', r.students[0].firstName === 'New');
    r = await (await post({ students: [rec('a', '202301001', { firstName: 'New', updatedAt: 200 })] })).json();
    check('a retried upload creates no duplicate', r.students.length === 1);
    r = await (await post({ students: [rec('b', '202301001', { updatedAt: 999 })] })).json();
    check('same number under another id is rejected, not duplicated', r.rejected.length === 1 && r.rejected[0].error === 'DUPLICATE_STUDENT_NUMBER' && r.students.length === 1);
    for (const [label, o] of [['8 digits', { number: '20230100' }], ['10 digits', { number: '2023010011' }], ['a letter', { number: '20230100x' }], ['a space', { number: '2023 0100' }], ['a JSON number', { number: 202301002 }], ['empty', { number: '' }]]) {
      r = await (await post({ students: [rec('c', '202301002', o)] })).json();
      check(`number with ${label} is rejected`, r.rejected.length === 1 && r.rejected[0].message.startsWith('number:'));
    }
    r = await (await post({ students: [rec('z', '000000123', { phone: '+260977123456', password: 'x' })] })).json();
    const z = r.students.find((s) => s.id === 'z');
    check('leading zeros kept; phone and password never stored', z && z.number === '000000123' && !('phone' in z) && !('password' in z));
    r = await (await post({ students: [] })).json();
    check('pull-only returns all', r.students.length === 2);
    srv.kill(); await sleep(300); srv = start(); await sleep(700);
    r = await (await post({ students: [] })).json();
    check('data survives a server restart (saved to disk)', r.students.length === 2);
  } catch (e) { console.log('ERROR', e); fails++; }
  srv.kill(); fs.existsSync(F) && fs.unlinkSync(F);
  process.exit(fails ? 1 : 0);
})();
