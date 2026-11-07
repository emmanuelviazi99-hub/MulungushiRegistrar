-- =================================================================
-- Migration 003 - the lecturer's class list (import, download, more groups).
--
-- 1. A student imported from the lecturer's spreadsheet has no NRC or date of
--    birth yet (the sheet has neither), so both may be empty. A student who
--    signs up fills them in; the emailed code proves the email is theirs. NRC
--    stays UNIQUE (several empty ones are allowed) and keeps its format check.
-- 2. "ICT with Education" joins the programmes (it is on the class list).
-- 3. A sign-up code may now point at an imported roster row AND carry the NRC
--    and date of birth the student typed, which are written to that row only
--    once the code is confirmed (so nobody can fill in someone else's row by
--    asking for a code they never confirm).
--
-- Groups need no change: lab_groups already holds each group's own capacity;
-- the import and "Add group" create G05, G06 ... as needed.
--
-- Keeps all your data. Run from the backend folder with:   npm run db:migrate
-- =================================================================

ALTER TABLE students
  MODIFY nrc           CHAR(11) NULL,
  MODIFY date_of_birth DATE     NULL;

INSERT INTO programmes (code, name)
  SELECT 'ICTED', 'ICT with Education' FROM DUAL
   WHERE NOT EXISTS (SELECT 1 FROM programmes WHERE code = 'ICTED');

ALTER TABLE verification_codes DROP CONSTRAINT chk_vcode_link;
ALTER TABLE verification_codes ADD CONSTRAINT chk_vcode_link CHECK (
  (purpose = 'STUDENT_SIGNUP' AND (student_id IS NOT NULL OR new_student IS NOT NULL)
                              AND staff_id IS NULL AND account_id IS NULL) OR
  (purpose = 'STAFF_SIGNUP'   AND staff_id   IS NOT NULL AND student_id IS NULL AND account_id IS NULL AND new_student IS NULL) OR
  (purpose = 'PASSWORD_RESET' AND account_id IS NOT NULL AND student_id IS NULL AND staff_id IS NULL   AND new_student IS NULL)
);
