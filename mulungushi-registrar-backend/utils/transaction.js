// utils/transaction.js
//
// Runs `work(conn)` inside one database transaction: commit if it returns,
// roll back if it throws, and always hand the connection back to the pool.
// Every multi-statement write in the app goes through this, so the
// begin / commit / rollback / release boilerplate exists exactly once.
//
//   const id = await withTransaction(async (conn) => {
//     await conn.query('UPDATE ...');
//     await conn.query('INSERT ...');
//     return something;
//   });

const pool = require('../config/database');

async function withTransaction(work) {
  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();
    const result = await work(conn);
    await conn.commit();
    return result;
  } catch (err) {
    await conn.rollback();
    throw err;
  } finally {
    conn.release();
  }
}

module.exports = { withTransaction };
