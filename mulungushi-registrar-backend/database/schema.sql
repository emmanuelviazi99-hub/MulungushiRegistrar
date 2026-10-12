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
--
-- 5. NRC and date of birth are captured when a lecturer adds a student.
--    nrc is UNIQUE (no two students can share one) and NOT NULL, but it is
--    deliberately NOT the primary key: a primary key must never change, an
--    NRC can be mistyped and corrected, and a primary key is copied into
--    every foreign key, URL and log line — which is the last place a national
--    ID number belongs. The immutable student_id stays the key.
--
-- 6. Accounts are created in real time through a verification-code flow
--    (verification_codes). A student can only sign up if their student
--    number + NRC + date of birth match a roster row a lecturer created.
--    A lecturer can only sign up if their email + employee ID are on
--    staff_roster, which only the server operator can edit (npm run staff:add).
--    Codes are stored as keyed HMACs, expire after 10 minutes, and allow 5
--    wrong attempts.
--
-- 7. Redundancy rules. Every fact is stored once; anything that can be
--    worked out from another column is a GENERATED column (computed by the
--    database, impossible to set by hand, so it can never disagree):
--      students.active   = (deleted_at IS NULL)       — no separate flag to forget
--      accounts.role     = which of student_id / staff_id is set
--      change_requests.is_pending = 1 while PENDING, so a UNIQUE key enforces
--                          "one open request per student per kind" (no racy SELECT)
--    Group-change and number-correction requests share one table
--    (change_requests) because they share their whole life-cycle: reason,
--    status, who resolved it and when.
--    Names live only in students / staff_roster (never copied onto accounts),
--    and programme / group codes live only in their reference tables (the API
--    and validation look them up, nothing hard-codes "CS" or "G01").
--    Two copies are deliberate history, not redundancy: change_requests keeps
--    the group / number the student had WHEN they asked, because after approval
--    the student row no longer says what it was. And a lecturer's login email
--    (accounts.email) starts as a copy of their staff_roster email: the roster
--    says who MAY sign up, the account is the login, and all login emails must
--    be unique across students and staff in one index.
-- =================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS operation_receipts;
DROP TABLE IF EXISTS change_requests;
DROP TABLE IF EXISTS number_correction_requests;   -- old name, replaced by change_requests
DROP TABLE IF EXISTS group_requests;               -- old name, replaced by change_requests
DROP TABLE IF EXISTS claim_codes;                  -- old hand-out codes, replaced by verification_codes
DROP TABLE IF EXISTS verification_codes;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS staff_roster;
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
-- students — the roster. Created by a lecturer; a student then signs up
-- against their row by proving student number + NRC + date of birth
-- -----------------------------------------------------------------
CREATE TABLE students (
  student_id      CHAR(36)     NOT NULL PRIMARY KEY,
  student_number  CHAR(9)      NOT NULL,
  nrc             CHAR(11)     NOT NULL,              -- 123456/10/1
  date_of_birth   DATE         NOT NULL,
  full_name       VARCHAR(100) NOT NULL,
  programme_id    INT          NOT NULL,
  group_id        INT          NULL,                 -- NULL = Unassigned
  version         INT UNSIGNED NOT NULL DEFAULT 1,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3)  NULL,                  -- set = soft-deleted
  active          TINYINT(1) AS (deleted_at IS NULL) STORED,  -- derived, never written
  CONSTRAINT uq_student_number UNIQUE (student_number),
  CONSTRAINT uq_student_nrc    UNIQUE (nrc),          -- stays reserved after a soft delete, like the number
  CONSTRAINT fk_students_programme FOREIGN KEY (programme_id) REFERENCES programmes(programme_id),
  CONSTRAINT fk_students_group     FOREIGN KEY (group_id)     REFERENCES lab_groups(group_id),
  CONSTRAINT chk_student_number_digits CHECK (student_number REGEXP '^[0-9]{9}$'),
  CONSTRAINT chk_student_nrc_format    CHECK (nrc REGEXP '^[0-9]{6}/[0-9]{2}/[0-9]$'),
  INDEX idx_students_group (group_id, active),
  INDEX idx_students_programme (programme_id, active),
  INDEX idx_students_name (full_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- staff_roster — lecturers approved to sign up (email + employee ID).
-- Only the server operator edits this (npm run staff:add); there is
-- deliberately no API that does, so a lecturer account cannot be
-- self-created by someone who is not on the list.
-- -----------------------------------------------------------------
CREATE TABLE staff_roster (
  staff_id    INT AUTO_INCREMENT PRIMARY KEY,
  employee_id VARCHAR(20)  NOT NULL,
  email       VARCHAR(191) NOT NULL,                  -- stored lower-case
  full_name   VARCHAR(100) NOT NULL,
  active      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_staff_employee_id (employee_id),
  UNIQUE KEY uq_staff_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- accounts — one row per login (student or lecturer)
-- Students sign in with their student number OR email; lecturers with email.
-- -----------------------------------------------------------------
CREATE TABLE accounts (
  account_id          INT AUTO_INCREMENT PRIMARY KEY,
  email               VARCHAR(191) NOT NULL,                -- stored lower-case; the login name
  phone               VARCHAR(16)  NULL,                    -- +260XXXXXXXXX
  password_hash       VARCHAR(255) NOT NULL,                -- bcrypt hash, never plain text
  student_id          CHAR(36) NULL,                        -- set for students ...
  staff_id            INT NULL,                             -- ... or for lecturers, never both
  role                ENUM('STUDENT','LECTURER')            -- derived from the link above, never written
                        AS (IF(staff_id IS NULL, 'STUDENT', 'LECTURER')) STORED,
  active              TINYINT(1) NOT NULL DEFAULT 1,
  failed_logins       TINYINT UNSIGNED NOT NULL DEFAULT 0,  -- consecutive wrong passwords
  locked_until        DATETIME(3) NULL,                     -- login lock after 5 wrong passwords
  password_changed_at DATETIME(3) NULL,                     -- tokens issued before this are rejected
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_account_email (email),
  UNIQUE KEY uq_account_student (student_id),               -- one account per student
  UNIQUE KEY uq_account_staff (staff_id),                   -- one account per staff member
  CONSTRAINT fk_accounts_student FOREIGN KEY (student_id) REFERENCES students(student_id),
  CONSTRAINT fk_accounts_staff   FOREIGN KEY (staff_id)   REFERENCES staff_roster(staff_id),
  CONSTRAINT chk_account_one_link CHECK ((student_id IS NULL) <> (staff_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- verification_codes — short-lived secret codes for sign-up and password
-- reset. code_hash is an HMAC-SHA256 keyed with a server secret, so a
-- copy of this table alone cannot be used to recover codes offline.
-- -----------------------------------------------------------------
CREATE TABLE verification_codes (
  code_id     INT AUTO_INCREMENT PRIMARY KEY,
  purpose     ENUM('STUDENT_SIGNUP','STAFF_SIGNUP','PASSWORD_RESET') NOT NULL,
  email       VARCHAR(191) NOT NULL,
  code_hash   CHAR(64)     NOT NULL,
  student_id  CHAR(36)     NULL,
  staff_id    INT          NULL,
  account_id  INT          NULL,
  phone       VARCHAR(16)  NULL,                      -- sign-up only: copied onto the account at completion
  attempts    TINYINT UNSIGNED NOT NULL DEFAULT 0,   -- wrong-or-right tries used (max 5)
  expires_at  DATETIME(3)  NOT NULL,
  verified_at DATETIME(3)  NULL,                      -- set when the right code was entered
  consumed_at DATETIME(3)  NULL,                      -- set when used up (or replaced by a newer code)
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_vcode_student FOREIGN KEY (student_id) REFERENCES students(student_id),
  CONSTRAINT fk_vcode_staff   FOREIGN KEY (staff_id)   REFERENCES staff_roster(staff_id),
  CONSTRAINT fk_vcode_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
  -- exactly the one link the purpose needs: who is signing up, or whose password is reset
  CONSTRAINT chk_vcode_link CHECK (
    (purpose = 'STUDENT_SIGNUP' AND student_id IS NOT NULL AND staff_id IS NULL     AND account_id IS NULL) OR
    (purpose = 'STAFF_SIGNUP'   AND staff_id   IS NOT NULL AND student_id IS NULL   AND account_id IS NULL) OR
    (purpose = 'PASSWORD_RESET' AND account_id IS NOT NULL AND student_id IS NULL   AND staff_id IS NULL)
  ),
  INDEX idx_vcode_lookup (email, purpose, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -----------------------------------------------------------------
-- change_requests — student-initiated, lecturer-approved changes.
--   kind GROUP   move to to_group_id (NULL = Unassigned); from_group_id is
--                the group they were in when they asked (history)
--   kind NUMBER  correct the student number to new_number; old_number is the
--                number they had when they asked (history)
-- The record itself never changes until a lecturer approves.
-- -----------------------------------------------------------------
CREATE TABLE change_requests (
  request_id    INT AUTO_INCREMENT PRIMARY KEY,
  kind          ENUM('GROUP','NUMBER') NOT NULL,
  student_id    CHAR(36) NOT NULL,
  from_group_id INT NULL,
  to_group_id   INT NULL,
  old_number    CHAR(9) NULL,
  new_number    CHAR(9) NULL,
  reason        VARCHAR(255) NULL,
  status        ENUM('PENDING','APPROVED','DECLINED') NOT NULL DEFAULT 'PENDING',
  requested_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at   DATETIME(3) NULL,
  resolved_by   INT NULL,
  -- "one open request per student per kind": is_pending is 1 while PENDING and
  -- NULL once resolved. A UNIQUE key never treats two NULLs as equal, so a
  -- student can have any amount of history but only one open request per kind.
  is_pending    TINYINT(1) AS (IF(status = 'PENDING', 1, NULL)) STORED,
  UNIQUE KEY uq_creq_one_pending (student_id, kind, is_pending),
  CONSTRAINT fk_creq_student  FOREIGN KEY (student_id)    REFERENCES students(student_id),
  CONSTRAINT fk_creq_from     FOREIGN KEY (from_group_id) REFERENCES lab_groups(group_id),
  CONSTRAINT fk_creq_to       FOREIGN KEY (to_group_id)   REFERENCES lab_groups(group_id),
  CONSTRAINT fk_creq_resolver FOREIGN KEY (resolved_by)   REFERENCES accounts(account_id),
  CONSTRAINT chk_creq_shape CHECK (
    (kind = 'GROUP'  AND old_number IS NULL AND new_number IS NULL) OR
    (kind = 'NUMBER' AND old_number IS NOT NULL AND new_number IS NOT NULL
                     AND from_group_id IS NULL AND to_group_id IS NULL
                     AND new_number REGEXP '^[0-9]{9}$')
  ),
  CONSTRAINT chk_creq_resolved CHECK ((status = 'PENDING') = (resolved_at IS NULL)),
  INDEX idx_creq_queue (kind, status, requested_at),
  INDEX idx_creq_student (student_id)
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
