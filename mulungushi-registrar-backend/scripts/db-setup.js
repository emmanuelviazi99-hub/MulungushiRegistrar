// scripts/db-setup.js
//
// Creates the database named in .env (if it does not exist) and loads
// database/schema.sql + database/seed.sql into it.
//
//   npm run db:setup
//
// WARNING: schema.sql starts by dropping the tables, so this wipes the data in
// that database. Use npm run db:migrate to upgrade a database and keep its data.

require('dotenv').config();
const fs = require('fs');
const path = require('path');
const mysql = require('mysql2/promise');

const NAME = /^[A-Za-z0-9_]+$/;

async function main() {
  const db = process.env.DB_NAME;
  if (!db || !NAME.test(db)) throw new Error('DB_NAME in .env must be letters, digits or _ only');
  const cfg = {
    host: process.env.DB_HOST,
    port: Number(process.env.DB_PORT) || 3306,
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    multipleStatements: true,
  };
  console.log(`Connecting to ${cfg.user}@${cfg.host}:${cfg.port} ...`);
  let conn = await mysql.createConnection(cfg);
  await conn.query(`CREATE DATABASE IF NOT EXISTS ${db} CHARACTER SET utf8mb4`);
  await conn.end();

  conn = await mysql.createConnection({ ...cfg, database: db });
  for (const file of ['schema.sql', 'seed.sql']) {
    const sql = fs.readFileSync(path.join(__dirname, '..', 'database', file), 'utf8');
    if (sql.length < 1000) throw new Error(`database/${file} is only ${sql.length} characters: it has been overwritten. Get it again from Git.`);
    await conn.query(sql);
    console.log(`Loaded database/${file}`);
  }
  const [[{ n }]] = await conn.query('SELECT COUNT(*) AS n FROM students');
  console.log(`Database ${db} is ready: ${n} students. Next: npm run seed:accounts`);
  await conn.end();
}

main().catch((err) => {
  console.error(`db:setup FAILED: ${err.message}`);
  process.exit(1);
});
