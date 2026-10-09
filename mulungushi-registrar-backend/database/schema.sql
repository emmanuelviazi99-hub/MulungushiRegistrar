-- =================================================================
-- Mulungushi University — Student Registration & Lab Group Management
-- MySQL / InnoDB schema
-- =================================================================
-- Run this once against an empty database:
--   mysql -u root -p mulungushi_registrar < database/schema.sql
--
-- Design notes (read these before you change anything):
--
-- 1. student_id is an immutable UUID (CHAR(36)), not the student_number.
--    It is the primary key everywhere a student is referenced, so a later
--    number correction never changes "who" the record is.
--
-- 2. student_number keeps its UNIQUE constraint even for soft-deleted rows.
--    That is deliberate: a deleted student's number stays reserved so it
--    cannot be accidentally re-registered by someone else (brief, section 19).
--
-- 3. students.version is an optimistic-concurrency counter. Every UPDATE to
--    a student's own fields or group increments it. A write that targets a
--    specific version and finds the row has moved on gets 0 affected rows,
--    which the application layer turns into a 409 CONFLICT.
--
-- 4. operation_receipts exists so a mutating request that is retried with
--    the same operationId (e.g. after the response was lost) replays the
--    stored result instead of repeating the effect. See services/idempotency.
-- =================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS otp_codes;
DROP TABLE IF EXISTS operation_receipts;
DROP TABLE IF EXISTS number_correction_requests;
DROP TABLE IF EXISTS group_requests;
DROP TABLE IF EXISTS claim_codes;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS students;
DROP TABLE IF EXISTS lab_groups;
DROP TABLE IF EXISTS programmes;

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------
-- programmes — reference data: CS, IT, DS
-- -----------------------------------------------------------------
CREATE TABLE programmes (
  programme_id INT AUTO_INCREMENT PRIMARY KEY,
  code         VARCHAR(5)  NOT NULL,
  name         VARCHAR(80) NOT NULL,
  UNIQUE KEY uq_programme_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- lab_groups — reference data: G01..G04, capacity 15 each
-- -----------------------------------------------------------------
CREATE TABLE lab_groups (
  group_id   INT AUTO_INCREMENT PRIMARY KEY,
  group_code VARCHAR(10) NOT NULL,
  capacity   TINYINT UNSIGNED NOT NULL DEFAULT 15,
  active     TINYINT(1) NOT NULL DEFAULT 1,
  UNIQUE KEY uq_group_code (group_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- students
-- -----------------------------------------------------------------
CREATE TABLE students (
  student_id      CHAR(36)     NOT NULL PRIMARY KEY,
  student_number  CHAR(9)      NOT NULL,
  full_name       VARCHAR(100) NOT NULL,
  programme_id    INT          NOT NULL,
  group_id        INT          NULL,                 -- NULL = Unassigned
  active          TINYINT(1)   NOT NULL DEFAULT 1,    -- 0 once soft-deleted
  version         INT UNSIGNED NOT NULL DEFAULT 1,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3)  NULL,
  deletion_marker VARCHAR(40)  NULL,
  CONSTRAINT uq_student_number UNIQUE (student_number),
  CONSTRAINT fk_students_programme FOREIGN KEY (programme_id) REFERENCES programmes(programme_id),
  CONSTRAINT fk_students_group     FOREIGN KEY (group_id)     REFERENCES lab_groups(group_id),
  CONSTRAINT chk_student_number_digits CHECK (student_number REGEXP '^[0-9]{9}$'),
  INDEX idx_students_group (group_id, active),
  INDEX idx_students_programme (programme_id, active),
  INDEX idx_students_name (full_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- accounts — one row per login (student or lecturer)
-- -----------------------------------------------------------------
CREATE TABLE accounts (
  account_id    INT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(60)  NOT NULL,
  password_hash VARCHAR(255) NOT NULL,                -- bcrypt hash, never plain text
  role          ENUM('STUDENT','LECTURER') NOT NULL,
  student_id    CHAR(36) NULL,                        -- NULL for lecturers
  active        TINYINT(1) NOT NULL DEFAULT 1,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_account_username (username),
  UNIQUE KEY uq_account_student (student_id),         -- one account per student
  CONSTRAINT fk_accounts_student FOREIGN KEY (student_id) REFERENCES students(student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- claim_codes — proves the registering person owns a lecturer-created record
-- -----------------------------------------------------------------
CREATE TABLE claim_codes (
  claim_id   INT AUTO_INCREMENT PRIMARY KEY,
  student_id CHAR(36) NOT NULL,
  code_hash  VARCHAR(255) NOT NULL,                   -- SHA-256 hex of the code, never the plain code
  expires_at DATETIME(3) NOT NULL,
  used_at    DATETIME(3) NULL,
  active     TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_claim_student FOREIGN KEY (student_id) REFERENCES students(student_id),
  INDEX idx_claim_student (student_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- group_requests — student-initiated group change, lecturer-approved
-- -----------------------------------------------------------------
CREATE TABLE group_requests (
  request_id    INT AUTO_INCREMENT PRIMARY KEY,
  student_id    CHAR(36) NOT NULL,
  from_group_id INT NULL,
  to_group_id   INT NULL,
  reason        VARCHAR(255) NULL,
  status        ENUM('PENDING','APPROVED','DECLINED') NOT NULL DEFAULT 'PENDING',
  requested_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at   DATETIME(3) NULL,
  resolved_by   INT NULL,
  CONSTRAINT fk_greq_student  FOREIGN KEY (student_id)    REFERENCES students(student_id),
  CONSTRAINT fk_greq_from     FOREIGN KEY (from_group_id) REFERENCES lab_groups(group_id),
  CONSTRAINT fk_greq_to       FOREIGN KEY (to_group_id)   REFERENCES lab_groups(group_id),
  CONSTRAINT fk_greq_resolver FOREIGN KEY (resolved_by)   REFERENCES accounts(account_id),
  INDEX idx_greq_status (status),
  INDEX idx_greq_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- number_correction_requests — student-initiated, lecturer-approved
-- -----------------------------------------------------------------
CREATE TABLE number_correction_requests (
  request_id   INT AUTO_INCREMENT PRIMARY KEY,
  student_id   CHAR(36) NOT NULL,
  old_number   CHAR(9) NOT NULL,
  new_number   CHAR(9) NOT NULL,
  reason       VARCHAR(255) NULL,
  status       ENUM('PENDING','APPROVED','DECLINED') NOT NULL DEFAULT 'PENDING',
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at  DATETIME(3) NULL,
  resolved_by  INT NULL,
  CONSTRAINT fk_nreq_student  FOREIGN KEY (student_id)  REFERENCES students(student_id),
  CONSTRAINT fk_nreq_resolver FOREIGN KEY (resolved_by) REFERENCES accounts(account_id),
  CONSTRAINT chk_nreq_new_number_digits CHECK (new_number REGEXP '^[0-9]{9}$'),
  INDEX idx_nreq_status (status),
  INDEX idx_nreq_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- operation_receipts — idempotent retry support (Challenge 2)
-- -----------------------------------------------------------------
CREATE TABLE operation_receipts (
  operation_id  CHAR(36) NOT NULL PRIMARY KEY,
  account_id    INT NOT NULL,
  request_hash  CHAR(64) NOT NULL,                    -- SHA-256 of the request body, detects reuse with different content
  status_code   SMALLINT NULL,
  response_json JSON NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_receipt_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- otp_codes — verification codes sent via email
-- -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS otp_codes (
  otp_id INT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(100) NOT NULL,
  otp_code VARCHAR(6) NOT NULL,
  expires_at DATETIME NOT NULL,
  used TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;