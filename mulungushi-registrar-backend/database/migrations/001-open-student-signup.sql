-- =================================================================
-- Migration 001 - students can sign up without being on the roster first.
--
-- For a database created from the schema.sql of 11-12 October 2026. It keeps
-- all your data (students, accounts). A new database does not need this: the
-- current schema.sql already includes it.
--
-- Run it from the backend folder with:   npm run db:migrate
-- (that also records it, so it never runs twice).
-- =================================================================

-- unused codes from before the change are cancelled, so the new rule below can be checked
UPDATE verification_codes SET consumed_at = NOW(3) WHERE consumed_at IS NULL;

ALTER TABLE verification_codes
  ADD COLUMN new_student JSON NULL AFTER account_id;

ALTER TABLE verification_codes DROP CONSTRAINT chk_vcode_link;

ALTER TABLE verification_codes ADD CONSTRAINT chk_vcode_link CHECK (
  (purpose = 'STUDENT_SIGNUP' AND (student_id IS NULL) <> (new_student IS NULL)
                              AND staff_id IS NULL AND account_id IS NULL) OR
  (purpose = 'STAFF_SIGNUP'   AND staff_id   IS NOT NULL AND student_id IS NULL AND account_id IS NULL AND new_student IS NULL) OR
  (purpose = 'PASSWORD_RESET' AND account_id IS NOT NULL AND student_id IS NULL AND staff_id IS NULL   AND new_student IS NULL)
);
