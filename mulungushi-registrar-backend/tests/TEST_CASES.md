# Documented test cases

Tests 1–8, 9–12, 13–15 and Challenges 1–2 below are automated in
`tests/auth.test.js` and `tests/capacity.test.js` (run `npm test`). Mobile-side
rows from the brief's minimum demonstration checklist (offline save, device
sync, accessibility) depend on the Android app and are documented here as
manual tests to run once Room/WorkManager exist — see the group's main
project README for that status.

For each test: starting data, steps, expected result, and where the
evidence comes from. Fill in "Actual result" and attach screenshots/log
output when you run these for your submission.

---

### Test 1 — Valid registration with a valid claim code
- **Starting data:** seeded student 202301001 (Chanda Mwansa), unused claim code `MU-7K4Q`.
- **Steps:** `POST /api/auth/register` with that number, code, a new username/password.
- **Expected:** 201, a token is returned, `accounts.student_id` links to the existing student — no new `students` row is created.
- **Evidence:** `tests/auth.test.js::Test 1`; confirm via `SELECT COUNT(*) FROM students WHERE student_number='202301001'` = 1.

### Test 2 — Invalid claim code
- **Starting data:** student 202301002 exists; wrong code `MU-WRNG` used.
- **Steps:** `POST /api/auth/register` with the wrong code.
- **Expected:** 401 `INVALID_CLAIM_CODE`, no account created.
- **Evidence:** `tests/auth.test.js::Test 2`.

### Test 3 — Claim code already used
- **Starting data:** seeded claim code `MU-OLD1` for 202301003, pre-marked `used_at`.
- **Steps:** `POST /api/auth/register` with that code.
- **Expected:** 409 `CLAIM_ALREADY_USED`.
- **Evidence:** `tests/auth.test.js::Test 3`.

### Test 3b — Expired claim code
- **Starting data:** seeded claim code `MU-EXP1` for 202301004, `expires_at` in the past.
- **Expected:** 410 `CLAIM_EXPIRED`.
- **Evidence:** `tests/auth.test.js::Test 3b`.

### Test 4 — Duplicate student number
- **Starting data:** 202301001 already exists.
- **Steps:** Lecturer `POST /api/students` with the same number.
- **Expected:** 409 `DUPLICATE_STUDENT_NUMBER`, original record untouched.
- **Evidence:** `tests/auth.test.js::Test 4`.

### Test 5 — Invalid student number
- **Steps:** Lecturer `POST /api/students` with `"12345"` as the number.
- **Expected:** 422 `VALIDATION_ERROR`.
- **Evidence:** `tests/auth.test.js::Test 5`.

### Test 6 — Student accesses own profile
- **Steps:** Student logs in, calls `GET /api/students/me`.
- **Expected:** 200, their own record only.
- **Evidence:** `tests/auth.test.js::Test 6`.

### Test 7 — Student attempts to access another student's profile
- **Steps:** Student token used against `GET /api/students/:id` (lecturer-only) for a different student.
- **Expected:** 403 `FORBIDDEN` — the endpoint itself is role-gated, not merely hidden in the UI.
- **Evidence:** `tests/auth.test.js::Test 7`.

### Test 8 — Student attempts a lecturer endpoint
- **Steps:** Student token used against `GET /api/students` (the full roster).
- **Expected:** 403 `FORBIDDEN`.
- **Evidence:** `tests/auth.test.js::Test 8`.

### Test 9 — Group with 14 active students accepts one more
- **Starting data:** G01 seeded at 14/15.
- **Steps:** Lecturer assigns a new student to G01.
- **Expected:** 200, group now 15/15.
- **Evidence:** `tests/capacity.test.js::Test 9`.

### Test 10 — Group with 15 active students rejects a new assignment
- **Steps:** Assign to a group already at 15/15.
- **Expected:** 409 `GROUP_FULL`.
- **Evidence:** `tests/capacity.test.js::Test 10`.

### Test 11 / Challenge 1 — Two simultaneous requests for the last place
- **Starting data:** a group with exactly 14/15, two different unassigned students.
- **Steps:** Fire both assignment requests with `Promise.all` (true concurrency, not sequential).
- **Expected:** exactly one 200, one 409 `GROUP_FULL`; final count is 15, never 16.
- **Evidence:** `tests/capacity.test.js::Challenge 1 / Test 11`, plus the 20-run repeat in the same file.

### Test 12 — Failed transfer
- **Steps:** Student in G02 attempts to move to a full G01.
- **Expected:** 409, student remains in G02 (`groupCode` unchanged on re-fetch).
- **Evidence:** `tests/capacity.test.js::Test 12`.

### Test 13 — Student requests number correction
- **Steps:** `POST /api/number-correction-requests` with a new 9-digit number.
- **Expected:** 201, status `PENDING`, no change yet to `students.student_number`.
- **Evidence:** `tests/auth.test.js::Test 13/14` (first half).

### Test 14 — Lecturer approves number correction
- **Steps:** `PUT /api/number-correction-requests/:id/approve`.
- **Expected:** `students.student_number` updated, `student_id` unchanged, request status `APPROVED`.
- **Evidence:** `tests/auth.test.js::Test 13/14` (second half).

### Test 15 — Lecturer deletes student
- **Steps:** `DELETE /api/students/:id`, then re-fetch, then delete again.
- **Expected:** record remains in MySQL with `active=0` and `deleted_at` set (confirm with a raw SQL query, not just the API); normal lookups 404; a second delete also 404 (seat released once).
- **Evidence:** `tests/auth.test.js::Test 15`.

### Challenge 2 — The response disappears
- **Steps:** Send a group assignment with an `operationId`. Resend the identical request with the same id. Resend again with the same id but a different `groupCode`.
- **Expected:** first call applies the effect once; the retry returns the same stored result without re-applying it (membership count stays at 1, not 2); the mismatched retry is rejected with `OPERATION_ID_REUSED`.
- **Evidence:** `tests/auth.test.js::Challenge 2`.
- **Full "kill the server mid-response" version:** for your submission video, additionally (a) start the request, (b) kill `node server.js` before the HTTP response is written but after the SQL has committed (add a `setTimeout` before `res.json` temporarily to create the window), (c) restart the server, (d) resend with the same `operationId`, (e) show the receipt row and the single resulting membership row in MySQL directly.

---

## Manual / mobile-dependent rows (from the brief's checklist)

These need the Android app's Room/WorkManager layer, which is a later
phase of the project (see the main README's phased plan). Document them
the same way once that layer exists:

| Row | What to test |
|---|---|
| Offline save and interrupted sync | Edit while airplane-mode on, confirm it survives an app restart, confirm WorkManager applies it exactly once on reconnect |
| Conflicting edit and remote deletion | Two devices, one edits while the other is deleted/edited remotely; confirm the conflict screen appears and nothing is silently overwritten |
| Search filters and accessibility | Combined search+programme+group filters; TalkBack pass; 200% font size; offline cache clearly labelled |
