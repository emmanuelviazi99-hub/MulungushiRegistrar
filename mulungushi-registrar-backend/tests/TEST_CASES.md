# Documented test cases

Everything below except the last section is automated: `npm test` runs 87
tests in six files against a throwaway database rebuilt from `schema.sql` +
`seed.sql`. The email tests run a real SMTP server inside the test, so they
check the actual Nodemailer path without needing the internet.

For your submission, run `npm test`, screenshot the summary, and for each brief
test below note the matching test name as evidence. The `file › test` names are
exactly what Jest prints.

| File | What it covers | Tests |
|---|---|---|
| `auth.test.js` | Student and lecturer sign-up with codes, code rules, password reset, login, lockout | 35 |
| `email.test.js` | Codes really sent by email (Nodemailer → SMTP), delivery failure, startup checks | 6 |
| `students.test.js` | Roster, NRC and birth date, who may see what, soft delete, retries, offline operations | 25 |
| `capacity.test.js` | The 15-seat limit on every path, under simultaneous requests | 9 |
| `requests.test.js` | Group-change and number-correction requests | 10 |
| `hardening.test.js` | Per-address rate limits | 2 |

---

## The brief's numbered tests

### Test 1 — Valid registration
- **Starting data:** seeded student 202301001, NRC 700001/10/1, born 2003-04-12; no account yet.
- **Steps:** `POST /auth/register/request` with those details and an email → read the emailed code → `POST /auth/register/verify` → `POST /auth/register/complete` with a password.
- **Expected:** 200, 200, 201 with a token; the new account is linked to the EXISTING student row (no second `students` row); the code never appears in any response.
- **Evidence:** `auth.test.js › student sign-up › the whole flow works: request -> verify -> complete -> sign in`; `email.test.js › the code arrives by email, is never in the API response, and the emailed code completes sign-up`.

### Test 2 — Invalid code
- **Steps:** verify with a wrong code five times, then with the right one.
- **Expected:** 400 `BAD_CODE` with `triesLeft` 4, 3, 2, 1, then 429 `TOO_MANY_TRIES`, and even the right code is refused afterwards; no account is created. Fifty guesses sent at the same instant still get only five checks.
- **Evidence:** `auth.test.js › verification code rules › 5 tries: …` and `› a flood of PARALLEL guesses still gets only 5 tries`.

### Test 3 — Code already used / expired
- **Expected:** a code works once (`NOT_VERIFIED` on reuse); after 10 minutes 410 `CODE_EXPIRED`; asking for a new code kills the old one.
- **Evidence:** `auth.test.js › … sign-up cannot be completed without verifying, and a verified code is single-use`, `› a code expires after 10 minutes`, `› asking again within 30 seconds is refused; later it works and the OLD code dies`.

### Test 3b — Details that are not on the roster
- **Expected:** a wrong NRC, wrong birth date or unknown number all get the same 404 `NOT_ON_ROSTER`, so the endpoint cannot be used to discover who is enrolled.
- **Evidence:** `auth.test.js › details that do not match the roster are refused with one identical answer`.

### Test 4 — Duplicate student number (and NRC)
- **Expected:** 409 `DUPLICATE_STUDENT_NUMBER` / `DUPLICATE_NRC`, including against a deleted student; the original record is untouched.
- **Evidence:** `students.test.js › NRC and student number are each unique — and the same goes for deleted students`.

### Test 5 — Invalid student number (and other fields)
- **Expected:** 422 `VALIDATION_ERROR` naming the field, nothing saved: 8 or 10 digits, letters, a malformed NRC, a birth date in the future or that does not exist, a malformed programme code.
- **Evidence:** `students.test.js › … is rejected and nothing is saved` (one line per case).

### Test 6 — Student reads own profile
- **Expected:** 200 with their own record, including their own full NRC and birth date.
- **Evidence:** `students.test.js › a student can read their own profile, including their own NRC and birth date`.

### Test 7 — Student tries to read another student
- **Expected:** 403 `FORBIDDEN` — the server checks the role, not just the app hiding the button.
- **Evidence:** `students.test.js › a student cannot read another student's record, nor list the roster …`.

### Test 8 — Student tries lecturer actions
- **Expected:** 403 on add, delete, edit-others and the lecturer-only offline operations; a student's own edit can never change their NRC, birth date or number, online or offline.
- **Evidence:** `students.test.js › a student cannot add, delete or edit others …` and `› a student can edit their name and programme, but never their NRC, birth date or number`.

### Test 9 — A group with 14 accepts one more
- **Evidence:** `capacity.test.js › Test 9 — a group with 14 active students can accept one more`.

### Test 10 — A full group refuses
- **Expected:** 409 `GROUP_FULL`.
- **Evidence:** `capacity.test.js › Test 10 — …`.

### Test 11 / Challenge 1 — Two (or ten) requests for the last seat at the same moment
- **Expected:** exactly one succeeds, the rest get `GROUP_FULL`; the group ends at 15, never 16 — on every path: assigning, creating a student into the group, the offline CREATE, and all three at once.
- **Evidence:** `capacity.test.js › Challenge 1 / Test 11 …`, `› Challenge 1, repeated — 20 resets …`, `› 10 simultaneous POST /api/students for the last seat …`, `› 10 simultaneous offline-sync CREATE …`, `› all three paths racing …`; and for approvals, `requests.test.js › approval re-checks the 15-seat limit …`.

### Test 12 — A failed transfer leaves the student where they were
- **Evidence:** `capacity.test.js › Test 12 — …`.

### Test 13 — Student asks for a number correction
- **Expected:** 201 `PENDING`; the number does not change yet; only one open request of each kind, even if five arrive at once.
- **Evidence:** `students.test.js › number correction: PENDING, then APPROVED …`; `requests.test.js › one open request per kind — enforced by the database even when five arrive at once`.

### Test 14 — Lecturer approves the correction
- **Expected:** the number changes, `student_id` and NRC do not, the request is `APPROVED`, and the student can sign in with the new number (not the old). A number someone else has is refused and the request stays pending.
- **Evidence:** `students.test.js › number correction: …`; `requests.test.js › approving a number that someone else already has is refused …`.

### Test 15 — Lecturer deletes a student
- **Expected:** the row stays with `deleted_at` set and `active` = 0 (check with SQL, not just the API); the seat is released once; a second delete is 404; the student can no longer sign in and their old token stops working.
- **Evidence:** `students.test.js › soft delete › the row stays, the seat is released once …`; `auth.test.js › a soft-deleted student can no longer sign in, and their old token stops working`.

### Challenge 2 — The response disappears
- **Steps:** send a change with an `operationId`; resend the identical request; resend the same id with different content.
- **Expected:** applied once; the retry returns the stored answer without re-applying it; the mismatched one gets 422 `OPERATION_ID_REUSED`.
- **Evidence:** `students.test.js › retrying the same operationId does not apply the change twice …`; `› an offline-queued CREATE … replays safely`.
- **For the video:** add a temporary `setTimeout` before `res.json` in `controllers/group.controller.js`, stop `node server.js` during that pause (after the database has committed), restart, resend with the same `operationId`, and show the single receipt row and single membership in MySQL.

---

## Additional security and reliability tests

| What is proved | Test |
|---|---|
| The code is stored only as a keyed hash | `auth.test.js › the code is stored only as a keyed hash, never in plain text` |
| A code works only for the email and purpose it was issued for | `auth.test.js › a code only works for the email it was sent to, and only for its own purpose` |
| 30 minutes to finish after verifying, not longer | `auth.test.js › after a code is accepted there are 30 minutes to finish, not longer` |
| Codes are accepted however they are typed (`mu-7k4qxp`, `7K4 QXP`) | `auth.test.js › the code is accepted however it is typed …` |
| Email that cannot be sent → `DELIVERY_FAILED`, no usable code left | `email.test.js › if the mail server refuses us …`; `auth.test.js › if delivery fails …` |
| Server will not start without SMTP settings; console codes refused in production | `email.test.js › startup checks for code delivery` |
| Lecturer sign-up needs email + employee ID on the staff list | `auth.test.js › lecturer sign-up › …` (3 tests) |
| Forgotten password never reveals who is registered | `auth.test.js › the answer is identical for a real and an unknown email …`; `email.test.js › password reset emails go only to registered addresses …` |
| Reset kills older tokens and unlocks the account | `auth.test.js › reset changes the password, and kills tokens issued before it` |
| 5 wrong passwords lock the account for 15 minutes | `auth.test.js › login › 5 wrong passwords lock the account …` |
| Offline EDIT/DELETE obey the same rules as the REST endpoints | `students.test.js › offline EDIT and DELETE obey exactly the same rules …` |
| Programme and group codes come from the database (adding G05 needs no code change) | `students.test.js › programme and group codes come from the database …` |
| Two lecturers approving one request apply it once | `requests.test.js › two lecturers approving the same request at the same moment apply it exactly once` |
| Requests cannot be approved through the wrong endpoint or by a student | `requests.test.js › the two kinds and the two roles stay separate › …` |
| Per-address limits on code requests (10) and logins (30) per 15 minutes | `hardening.test.js` |

### The tests were checked against deliberately broken code

Each of these changes was made to the code on purpose; in every case at least
one test failed, which shows the tests really guard the rule:

unlimited code attempts · old token surviving a password reset · no login
lockout · completing sign-up without verifying · no 30-minute window ·
reusing a used code · `NOT_ON_ROSTER` revealing which detail was wrong ·
creating a student into a group without the lock · removing every lock from the
seat check · dropping the one-open-request key · approving without locking the
request · skipping the name check on offline edits · deleting a student without
releasing the seat · letting a student change their own NRC offline.

---

## Manual / mobile-dependent rows (from the brief's checklist)

These need the Android app's Room / WorkManager layer:

| Row | What to test |
|---|---|
| Offline save and interrupted sync | Edit in airplane mode, restart the app, reconnect: WorkManager applies it exactly once |
| Conflicting edit and remote deletion | Two devices: one edits while the other deletes or edits; the conflict screen appears and nothing is silently overwritten (`VERSION_CONFLICT`, `STUDENT_NOT_FOUND`) |
| Search filters and accessibility | Combined search + programme + group filters; TalkBack; 200% font size; offline data clearly labelled |
| Sign-up on the phone | Real email arrives; code typed in lower case still works; "resend" waits 30 seconds |
