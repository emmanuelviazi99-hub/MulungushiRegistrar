// scripts/db-migrate.js
//
// Brings an existing database up to date WITHOUT losing its data: runs each file
// in database/migrations/ that has not been run on this database yet, in name
// order, and records it in the schema_migrations table.
//
//   npm run db:migrate
//
// A database created from the current schema.sql already has every migration
// recorded, so this does nothing there.

require('dotenv').config();
const fs = require('fs');
const path = require('path');
const mysql = require('mysql2/promise');

async function main() {
  const conn = await mysql.createConnection({
    host: process.env.DB_HOST,
    port: Number(process.env.DB_PORT) || 3306,
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    multipleStatements: true,
  });
  await conn.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
    name       VARCHAR(100) NOT NULL PRIMARY KEY,
    applied_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`);
  const [rows] = await conn.query('SELECT name FROM schema_migrations');
  const done = new Set(rows.map((r) => r.name));

  const dir = path.join(__dirname, '..', 'database', 'migrations');
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
  let ran = 0;
  for (const file of files) {
    if (done.has(file)) continue;
    console.log(`Running ${file} ...`);
    await conn.query(fs.readFileSync(path.join(dir, file), 'utf8'));
    await conn.query('INSERT INTO schema_migrations (name) VALUES (?)', [file]);
    ran += 1;
  }
  console.log(ran ? `Done: ${ran} migration(s) applied to ${process.env.DB_NAME}.` : `${process.env.DB_NAME} is already up to date.`);
  await conn.end();
}

main().catch((err) => {
  console.error(`db:migrate FAILED: ${err.message}`);
  process.exit(1);
});
