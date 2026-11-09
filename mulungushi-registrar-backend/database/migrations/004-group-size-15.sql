-- =================================================================
-- Migration 004 - every group is out of 15 again.
--
-- The first class-list import raised the limit of groups whose list was longer
-- than 15 (or that already had a few members), e.g. G01 to 18. When members then
-- left, the dashboard showed "15/18" with three empty places nobody should take.
--
-- From now on a group's limit stays 15. A group the class list filled past 15
-- keeps all its members and shows as full ("16/16"); once members leave it reads
-- "15/15", and it takes new members only below 15. This puts back the limit of
-- the groups that were raised.
--
-- Keeps all your data. Run from the backend folder with:   npm run db:migrate
-- =================================================================

UPDATE lab_groups SET capacity = 15 WHERE capacity > 15;
