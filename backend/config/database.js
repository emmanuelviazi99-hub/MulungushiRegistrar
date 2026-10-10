// config/database.js
//
// Creates one shared MySQL connection pool for the whole app.
// A pool, not a single connection, lets several requests talk to the
// database at the same time without waiting on each other, and it
// automatically opens a fresh connection if one drops.
//
// `promise()` gives us the async/await-friendly version of mysql2 instead
// of the older callback style.

require('dotenv').config();
const mysql = require('mysql2');

const pool = mysql
  .createPool({
    host: process.env.DB_HOST,
    port: Number(process.env.DB_PORT) || 3306,
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
    connectionLimit: Number(process.env.DB_CONNECTION_LIMIT) || 10,
    waitForConnections: true,
    queueLimit: 0,
    decimalNumbers: true,
  })
  .promise();

module.exports = pool;
