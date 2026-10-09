-- =================================================================
-- Seed data — reference tables + fictitious demo students/claim codes.
-- Run AFTER schema.sql:
--   mysql -u root -p mulungushi_registrar < database/seed.sql
--
-- This file does NOT create the lecturer account, because that needs a
-- bcrypt hash and bcrypt only exists on the Node side. After running this
-- file, run:
--   npm run seed:accounts
-- which creates the lecturer login (see database/seed-accounts.js).
--
-- All names, numbers and claim codes below are fictitious, for demo use only.
-- =================================================================

-- -----------------------------------------------------------------
-- Programmes
-- -----------------------------------------------------------------
INSERT INTO programmes (code, name) VALUES
  ('CS', 'Computer Science'),
  ('IT', 'Information Technology'),
  ('DS', 'Data Science');

-- -----------------------------------------------------------------
-- Lab groups (capacity 15 each, matches the brief's hard limit)
-- -----------------------------------------------------------------
INSERT INTO lab_groups (group_code, capacity) VALUES
  ('G01', 15),
  ('G02', 15),
  ('G03', 15),
  ('G04', 15);

-- -----------------------------------------------------------------
-- Fictitious students
-- Uses MySQL's UUID() to generate the immutable student_id.
-- G01 is deliberately seeded with 14 active members so Challenge 1
-- (two clients racing for the last place) can be demonstrated immediately.
-- -----------------------------------------------------------------
SET @cs := (SELECT programme_id FROM programmes WHERE code = 'CS');
SET @it := (SELECT programme_id FROM programmes WHERE code = 'IT');
SET @ds := (SELECT programme_id FROM programmes WHERE code = 'DS');
SET @g01 := (SELECT group_id FROM lab_groups WHERE group_code = 'G01');
SET @g02 := (SELECT group_id FROM lab_groups WHERE group_code = 'G02');
SET @g03 := (SELECT group_id FROM lab_groups WHERE group_code = 'G03');
SET @g04 := (SELECT group_id FROM lab_groups WHERE group_code = 'G04');

-- 13 ordinary students spread across groups
INSERT INTO students (student_id, student_number, full_name, programme_id, group_id, active) VALUES
  (UUID(), '202301001', 'Chanda Mwansa',      @cs, @g01, 1),
  (UUID(), '202301002', 'Bwalya Phiri',       @it, @g01, 1),
  (UUID(), '202301003', 'Mutinta Banda',      @ds, @g01, 1),
  (UUID(), '202301004', 'Natasha Zulu',       @cs, @g01, 1),
  (UUID(), '202301005', 'Kelvin Tembo',       @it, @g01, 1),
  (UUID(), '202301006', 'Bornface Sikaonga',  @cs, @g01, 1),
  (UUID(), '202301007', 'Precious Mulenga',   @ds, @g01, 1),
  (UUID(), '202301008', 'Given Chileshe',     @it, @g01, 1),
  (UUID(), '202301009', 'Mwansa Kabwe',       @cs, @g01, 1),
  (UUID(), '202301010', 'Chileshe Mumba',     @ds, @g01, 1),
  (UUID(), '202301011', 'Lweendo Sinyama',    @it, @g01, 1),
  (UUID(), '202301012', 'Joseph Banda',       @cs, @g01, 1),
  (UUID(), '202301013', 'Mercy Nyirenda',     @ds, @g01, 1),
  (UUID(), '202301014', 'Alick Mwale',        @it, @g01, 1),  -- G01 now at 14/15
  (UUID(), '202301015', 'Grace Lungu',        @cs, @g02, 1),
  (UUID(), '202301016', 'Felix Ngoma',        @it, @g03, 1),
  (UUID(), '202301017', 'Rabecca Daka',       @ds, @g04, 1),
  (UUID(), '202301018', 'Innocent Mbewe',     @cs, NULL, 1);  -- Unassigned

-- One soft-deleted student, to demo that their number stays reserved
INSERT INTO students (student_id, student_number, full_name, programme_id, group_id, active, deleted_at, deletion_marker)
VALUES (UUID(), '202301099', 'Former Student', @cs, NULL, 0, NOW(3), CONCAT('DEL-', UUID()));

-- -----------------------------------------------------------------
-- Claim codes for a few of the seeded students, so registration can be
-- demoed immediately. Plain codes are listed here ONLY because this is
-- fictitious seed data for marking purposes — never do this with real
-- student data. The table itself stores only the SHA-256 hash.
--
--   student_number  claim_code   (give this pair to the marker/demo)
--   202301001       MU-7K4Q
--   202301002       MU-9P2X
--   202301018       MU-3H8Z   (Unassigned student, good for showing a
--                               fresh registration with no group yet)
-- -----------------------------------------------------------------
INSERT INTO claim_codes (student_id, code_hash, expires_at)
SELECT student_id, SHA2('MU-7K4Q', 256), DATE_ADD(NOW(3), INTERVAL 30 DAY)
FROM students WHERE student_number = '202301001';

INSERT INTO claim_codes (student_id, code_hash, expires_at)
SELECT student_id, SHA2('MU-9P2X', 256), DATE_ADD(NOW(3), INTERVAL 30 DAY)
FROM students WHERE student_number = '202301002';

INSERT INTO claim_codes (student_id, code_hash, expires_at)
SELECT student_id, SHA2('MU-3H8Z', 256), DATE_ADD(NOW(3), INTERVAL 30 DAY)
FROM students WHERE student_number = '202301018';

-- An already-used claim code, to demo CLAIM_ALREADY_USED
INSERT INTO claim_codes (student_id, code_hash, expires_at, used_at)
SELECT student_id, SHA2('MU-OLD1', 256), DATE_ADD(NOW(3), INTERVAL 30 DAY), NOW(3)
FROM students WHERE student_number = '202301003';

-- An expired claim code, to demo CLAIM_EXPIRED
INSERT INTO claim_codes (student_id, code_hash, expires_at)
SELECT student_id, SHA2('MU-EXP1', 256), DATE_SUB(NOW(3), INTERVAL 1 DAY)
FROM students WHERE student_number = '202301004';
