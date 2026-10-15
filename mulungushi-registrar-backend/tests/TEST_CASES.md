# Documented test cases

Everything below except the last section is automated: `npm test` runs 124
tests in nine files against a throwaway database rebuilt from `schema.sql` +
`seed.sql`. The email tests run a real SMTP server inside the test, so they
check the actual Nodemailer path without needing the internet.

For your submission, run `npm test`, screenshot the summary, and for each brief
test below note the matching test name as evidence. The `file › test` names are
exactly what Jest prints.

| File | What it covers | Tests |
|---|---|---|
| `auth.test.js` | Student and lecturer sign-up with codes (including self sign-up by a student not yet on the roster), code rules, password reset, login, lockout | 40 |
| `email.test.js` | Codes really sent by email (Nodemailer → SMTP), delivery failure, startup checks | 6 |
| `students.test.js` | Roster, NRC and birth date, who may see what, soft delete, retries, offline operations | 25 |
| `capacity.test.js` | The 15-seat limit on every path, under simultaneous requests | 9 |
| `requests.test.js` | Group-change and number-correction requests | 10 |
| `hardening.test.js` | Per-address rate limits | 2 |
| `app-screens.test.js` | What the app's roster and request screens use: the lecturer's one-step save, a student's own requests, the Answered history, my group | 14 |
| `password-change.test.js` | Change password while signed in: current password checked, old one stops, other phones signed out, guesses lock | 5 |
| `idempotency.test.js` | The `Idempotency-Key` header the app's offline queue sends: every change applied once however often it is sent | 13 |

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
- **Evidence:** `students.test.js › retrying the same operationId does not apply the change twice …`; `› an offline-queued CREATE … replays safely`; and for every other change the app makes (save, add, delete, approve, requests, withdraw), `idempotency.test.js › a retried change is applied once, and the retry gets the first answer › …` (8 tests, including ten copies sent at the same instant) and `› a key cannot be misused › …` (5 tests).
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
| Per-address limits on code requests (20, adjustable in `.env`) and logins (30) per 15 minutes | `hardening.test.js` |
| A group the lecturer assigns is what the student sees after signing in again (the bug reported on the phone) | `app-screens.test.js › the lecturer assigns a group and the student sees it › …` |
| The lecturer's save (name, programme, number, group) is all-or-nothing: a full group or a taken number changes nothing | `app-screens.test.js › PUT /students/:id … › a full group refuses the WHOLE save …`; `… a number someone else has is refused …` |
| Two lecturers editing the same student: the second save is refused, not silently overwriting | `app-screens.test.js › … › an out-of-date version is refused …` |
| A lecturer save that does what the student asked for answers their request as approved | `app-screens.test.js › … › doing exactly what an open request asked for …` |
| A student can withdraw only their own open request; answered ones stay | `app-screens.test.js › a student's own requests › …` |
| A student sees only names and numbers of their own group's members | `app-screens.test.js › GET /groups/mine › …` |
| A student not on the roster can sign up alone; the student row appears only after the code is confirmed | `auth.test.js › self sign-up: a student who is not on the roster yet › signs up from the app alone …` |
| An abandoned self sign-up leaves nothing behind; the number stays free | `auth.test.js › self sign-up … › an abandoned sign-up leaves nothing behind …` |
| A new student must choose a programme; forgetting it does not use up the code | `auth.test.js › self sign-up … › a new student must choose a programme …` |
| Two people racing for the same new number: exactly one account, the other gets `ALREADY_REGISTERED` | `auth.test.js › self sign-up … › two people racing to claim the same new number …` |
| A roster number still needs its own NRC and birth date; a new number cannot reuse someone else's NRC | `auth.test.js › a number already on the roster needs its own NRC and birth date …` |

### The tests were checked against deliberately broken code

Each of these changes was made to the code on purpose; in every case at least
one test failed, which shows the tests really guard the rule:

unlimited code attempts · old token surviving a password reset · no login
lockout · completing sign-up without verifying · no 30-minute window ·
reusing a used code · `NOT_ON_ROSTER` revealing which detail was wrong ·
letting a new number reuse another student's NRC · ignoring the group in the
lecturer's save · saving the lecturer's changes outside one transaction ·
letting a student erase an answered request · creating the self
sign-up student before the code is confirmed · skipping the roster NRC check ·
creating a student into a group without the lock · removing every lock from the
seat check · dropping the one-open-request key · approving without locking the
request · skipping the name check on offline edits · deleting a student without
releasing the seat · letting a student change their own NRC offline.

---

## Manual / mobile-dependent rows (from the brief's checklist)

The app's Room / WorkManager layer handles these; the steps to show them on a
phone are in `STORAGE_AND_SYNC.md` (project root). The same code is also run
against the real backend by a live check (offline edits, restart, reconnect,
conflict, refusal).

| Row | What to test |
|---|---|
| Offline save and interrupted sync | Edit in airplane mode, close the app, reopen it (still offline): the edit is there, marked "Waiting to upload". Reconnect: WorkManager uploads it once (check in MySQL) |
| Conflicting edit and remote deletion | Two devices: one edits offline while the other edits or deletes the same student; on reconnect the Sync screen shows "Changed by someone else" with both versions and Keep mine / Use theirs; nothing is silently overwritten (`VERSION_CONFLICT`, `STUDENT_NOT_FOUND`) |
| Search filters and accessibility | Roster: type part of a name or number, then pick a programme and a group; the three combine, and work offline on the saved roster. TalkBack: each roster row is read as one sentence ("Chanda Mwansa, number 2 0 2 3 …, CS, group G01"), the result count is announced when it changes, decorative icons are skipped, screen titles are headings. Settings → Display → font size largest (or 200%): buttons, tabs and bars grow instead of cutting text off. Offline data: rows changed on the phone say "Waiting to upload"; the amber strip says when the data on screen was saved |
| Sign-up on the phone | Real email arrives; code typed in lower case still works; "resend" waits 30 seconds |
