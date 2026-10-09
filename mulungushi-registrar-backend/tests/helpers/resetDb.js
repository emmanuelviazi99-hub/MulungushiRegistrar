// tests/helpers/resetDb.js
//
// Rebuilds a SEPARATE test database from schema.sql + seed.sql before each
// test run, so tests never touch your real dev data and always start from
// the same known fixture (G01 seeded with 14 active students — see
// database/seed.sql — which is exactly what Challenge 1 needs).
//
// Point TEST_DB_NAME at a database that is safe to drop and recreate, e.g.
// "mulungushi_registrar_test". Never point it at a database with real data.

require('dotenv').config();
const fs = require('fs');
const path = require('path');
const mysql = require('mysql2/promise');

const TEST_DB_NAME = process.env.TEST_DB_NAME || 'mulungushi_registrar_test';

async function resetDb() {
  const admin = await mysql.createConnection({
    host: process.env.DB_HOST,
    port: Number(process.env.DB_PORT) || 3306,
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    multipleStatements: true,
  });

  await admin.query(`DROP DATABASE IF EXISTS \`${TEST_DB_NAME}\`;`);
  await admin.query(`CREATE DATABASE \`${TEST_DB_NAME}\` CHARACTER SET utf8mb4;`);
  await admin.changeUser({ database: TEST_DB_NAME });

  const schema = fs.readFileSync(path.join(__dirname, '../../database/schema.sql'), 'utf8');
  const seed = fs.readFileSync(path.join(__dirname, '../../database/seed.sql'), 'utf8');
  await admin.query(schema);
  await admin.query(seed);

  await admin.end();
}

module.exports = { resetDb, TEST_DB_NAME };
