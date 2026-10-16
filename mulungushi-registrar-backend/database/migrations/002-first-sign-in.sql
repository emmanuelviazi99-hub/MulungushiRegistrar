-- =================================================================
-- Migration 002 - remember each account's first sign-in.
--
-- The app greets a person "Welcome, Riley!" on their first sign-in (a finished
-- sign-up counts as one) and "Welcome back, Riley!" after that. The server
-- decides which, so it is the same on every phone and survives sign-out and
-- reinstalling the app: first_signed_in_at is NULL until the first successful
-- sign-in, which sets it in one atomic UPDATE.
--
-- Accounts that already exist have been used, so they count as returning.
-- Keeps all your data. Run from the backend folder with:   npm run db:migrate
-- =================================================================

ALTER TABLE accounts
  ADD COLUMN first_signed_in_at DATETIME(3) NULL AFTER password_changed_at;

UPDATE accounts SET first_signed_in_at = created_at WHERE first_signed_in_at IS NULL;
