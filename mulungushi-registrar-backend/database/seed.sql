-- =================================================================
-- Seed data — reference tables + fictitious demo students and staff.
-- Run AFTER schema.sql:
--   mysql -u root -p mulungushi_registrar < database/seed.sql
--
-- This file does NOT create any login, because that needs a bcrypt hash and
-- bcrypt only exists on the Node side. After running this file, run:
--   npm run seed:accounts
-- which creates the demo lecturer login (see database/seed-accounts.js).
-- Everyone else signs up through the verification-code flow in the app.
--
-- All names, numbers, NRCs, birth dates, emails and employee IDs below are
-- fictitious, for demo use only.
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

-- 18 ordinary students spread across groups. Each has a fictitious NRC
-- (700001/10/1 ... 700018/10/1) and a date of birth — these are what a student
-- must type, together with their student number, to sign up.
INSERT INTO students (student_id, student_number, nrc, date_of_birth, full_name, programme_id, group_id) VALUES
  (UUID(), '202301001', '700001/10/1', '2003-04-12', 'Chanda Mwansa'     , @cs, @g01),
  (UUID(), '202301002', '700002/10/1', '2002-11-03', 'Bwalya Phiri'      , @it, @g01),
  (UUID(), '202301003', '700003/10/1', '2003-01-25', 'Mutinta Banda'     , @ds, @g01),
  (UUID(), '202301004', '700004/10/1', '2002-07-19', 'Natasha Zulu'      , @cs, @g01),
  (UUID(), '202301005', '700005/10/1', '2003-09-30', 'Kelvin Tembo'      , @it, @g01),
  (UUID(), '202301006', '700006/10/1', '2002-02-14', 'Bornface Sikaonga' , @cs, @g01),
  (UUID(), '202301007', '700007/10/1', '2003-06-08', 'Precious Mulenga'  , @ds, @g01),
  (UUID(), '202301008', '700008/10/1', '2002-12-21', 'Given Chileshe'    , @it, @g01),
  (UUID(), '202301009', '700009/10/1', '2003-03-17', 'Mwansa Kabwe'      , @cs, @g01),
  (UUID(), '202301010', '700010/10/1', '2002-08-05', 'Chileshe Mumba'    , @ds, @g01),
  (UUID(), '202301011', '700011/10/1', '2003-10-26', 'Lweendo Sinyama'   , @it, @g01),
  (UUID(), '202301012', '700012/10/1', '2002-05-11', 'Joseph Banda'      , @cs, @g01),
  (UUID(), '202301013', '700013/10/1', '2003-07-02', 'Mercy Nyirenda'    , @ds, @g01),
  (UUID(), '202301014', '700014/10/1', '2002-09-18', 'Alick Mwale'       , @it, @g01),  -- G01 now at 14/15
  (UUID(), '202301015', '700015/10/1', '2003-12-09', 'Grace Lungu'       , @cs, @g02),
  (UUID(), '202301016', '700016/10/1', '2002-03-23', 'Felix Ngoma'       , @it, @g03),
  (UUID(), '202301017', '700017/10/1', '2003-05-30', 'Rabecca Daka'      , @ds, @g04),
  (UUID(), '202301018', '700018/10/1', '2002-10-15', 'Innocent Mbewe'    , @cs, NULL);  -- Unassigned

-- One soft-deleted student, to demo that their number stays reserved
-- (students.active is computed from deleted_at, so setting deleted_at is all it takes)
INSERT INTO students (student_id, student_number, nrc, date_of_birth, full_name, programme_id, group_id, deleted_at)
VALUES (UUID(), '202301099', '700099/10/1', '2001-01-01', 'Former Student', @cs, NULL, NOW(3));

-- -----------------------------------------------------------------
-- Staff roster — the lecturers allowed to sign up. To add more, run
--   npm run staff:add -- --email x@mulungushi.edu.zm --employee-id EMP-2001 --name "Full Name"
--
--   EMP-1001  b.nyirenda@mulungushi.edu.zm   gets a ready-made login from
--                                            `npm run seed:accounts`
--   EMP-1002  m.chilufya@mulungushi.edu.zm   left unregistered, so the
--                                            lecturer sign-up flow can be demoed
-- -----------------------------------------------------------------
INSERT INTO staff_roster (employee_id, email, full_name) VALUES
  ('EMP-1001', 'b.nyirenda@mulungushi.edu.zm', 'Bwalya Nyirenda'),
  ('EMP-1002', 'm.chilufya@mulungushi.edu.zm', 'Mutale Chilufya');
