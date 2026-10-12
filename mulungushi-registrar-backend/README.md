# Mulungushi Registrar — Backend

Node.js + Express + MySQL backend for the ICT361 Student Registration and Lab
Group Management app (Android package `zm.ac.mulungushi.registrar`).

What it does:

- **Accounts are created in real time** by the people themselves, with a
  **verification code sent by email (Nodemailer)**:
  - a **student** gives their name, email, student number, NRC and date of
    birth; the number + NRC + birth date must match the row a lecturer added
  - a **lecturer** gives their name, email and employee ID, which must be on
    the staff list the server operator keeps
  - the code (e.g. `MU-7K4QXP`) is emailed, typed back into the app, and then
    the person chooses a password
- Lecturers manage the roster and the four lab groups, with a hard **15-seat
  limit** that holds even when many phones act at the same moment.
- Students ask for a group change or a student-number correction; a lecturer
  approves or declines.
- Offline changes from the app are replayed safely (`operationId`), never
  applied twice.

---

## 1. Requirements

- Node.js 18 or newer
- MySQL 8 (or MariaDB 10.6+)
- An email account that can send mail over SMTP. A Gmail account with an
  **app password** is the simplest; see section 3.

## 2. Install and create the database

```bash
npm install
cp .env.example .env          # Windows: copy .env.example .env
```

Create the database, then load the tables and demo data. In MySQL Workbench you
can instead open each `.sql` file and run it with the lightning-bolt button.

```bash
mysql -u root -p -e "CREATE DATABASE mulungushi_registrar CHARACTER SET utf8mb4;"
mysql -u root -p mulungushi_registrar < database/schema.sql
mysql -u root -p mulungushi_registrar < database/seed.sql
```

> `schema.sql` starts by dropping the old tables, so running it again (or over
> the earlier version that had `claim_codes`) gives you a clean database. It
> deletes all data in `mulungushi_registrar`.

## 3. Fill in `.env`

Open `.env` and set at least:

| Setting | What to put |
|---|---|
| `DB_USER`, `DB_PASSWORD` | Your MySQL login (e.g. `root` and the password you chose when installing MySQL) |
| `JWT_SECRET` | A long random string. Make one with `node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"` |
| `SMTP_*` | The email account that sends the codes (below) |

**Sending codes with Gmail (Nodemailer):**

1. Turn on 2-Step Verification on the Google account.
2. Google Account → Security → 2-Step Verification → **App passwords**. Create
   one called "Mulungushi Registrar" and copy the 16 letters.
3. In `.env`:
   ```
   CODE_DELIVERY=email
   SMTP_HOST=smtp.gmail.com
   SMTP_PORT=465
   SMTP_SECURE=true
   SMTP_USER=yourname@gmail.com
   SMTP_PASS=abcdabcdabcdabcd
   SMTP_FROM="Mulungushi Registrar <yourname@gmail.com>"
   ```
4. Check it works before anyone signs up:
   ```bash
   npm run email:test -- yourname@gmail.com
   ```
   You should see `SMTP login OK` and receive a test email.

Other choices are in `.env.example`: **Mailtrap** (every email is caught in a
web inbox, handy for testing with made-up addresses) or the university's own
mail server. `CODE_DELIVERY=console` prints codes in the terminal instead, for
working with no mail account at all; the server refuses that setting in
production. SMS through Twilio can be added on top: `CODE_DELIVERY=email,sms`.

> If a value contains `#`, wrap it in double quotes (`"Lecturer#2025"`).
> Otherwise everything after the `#` is silently dropped.

The server checks these settings when it starts and stops with a clear message
if something is missing (no JWT secret, SMTP not filled in, and so on).

## 4. Demo logins and the staff list

```bash
npm run seed:accounts
```

This creates a ready-made lecturer login from the `SEED_LECTURER_*` values in
`.env` (by default `b.nyirenda@mulungushi.edu.zm` / `Lecturer#2025`, employee
ID `EMP-1001`). Change the password before anyone outside your group sees it.

Lecturers can only sign up if they are on the **staff list**. Add one with:

```bash
npm run staff:add -- --email j.banda@mulungushi.edu.zm --employee-id EMP-2001 --name "Joseph Banda"
```

There is deliberately no API endpoint for this: a lecturer account can edit the
whole roster, so it must never be creatable by someone who only knows an email
address.

Demo data in `seed.sql` (all fictitious):

| Who | Use for |
|---|---|
| Students `202301001` – `202301018`, NRC `700001/10/1` … `700018/10/1`, birth dates listed in `seed.sql` | Student sign-up. E.g. `202301001`, `700001/10/1`, `2003-04-12` |
| Lecturer `m.chilufya@mulungushi.edu.zm`, `EMP-1002` | Lecturer sign-up (on the staff list, no account yet) |
| Group G01 | Holds 14 of 15, so "racing for the last seat" can be shown at once |

## 5. Run it

```bash
npm start            # or: npm run dev   (restarts when you save a file)
```

```
Mulungushi Registrar backend listening on port 3000
Verification codes are sent by: email (SMTP smtp.gmail.com:465)
```

Check it is alive: open `http://localhost:3000/health`.

**From the Android emulator** use `http://10.0.2.2:3000/api/` — `10.0.2.2` is
how the emulator reaches your laptop. A real phone on the same Wi-Fi uses your
laptop's IP address instead. Use HTTPS on a real host for the final submission.

## 6. Tests

```bash
npm test
```

Set `TEST_DB_NAME` in `.env` to a database that is safe to delete (the default
`mulungushi_registrar_test` is fine): the tests drop and rebuild it from
`schema.sql` + `seed.sql`, and never touch your real data. The email tests run
their own small mail server inside the test, so they need no internet or real
mail account. `tests/TEST_CASES.md` lists what each test proves.

## 7. Project structure

```
server.js                  wires everything together; checks config, then listens
config/
  database.js              one shared MySQL connection pool
  env.js                   refuses to start with unsafe or incomplete settings
middleware/
  auth.js                  checks the JWT and that the account is still active
  role.js                  STUDENT / LECTURER only routes
  validation.js            field rules (express-validator)
  rateLimit.js             per-address limits on code requests and logins
  errorHandler.js          every error -> { success:false, error, message }
routes/                    URL -> controller, one file per area
controllers/               thin: read the request, call a service, send the reply
services/
  student.service.js       every read and write of a roster row
  group.service.js         THE 15-seat check, used by every path into a group
  request.service.js       group-change and number-correction requests
  code.service.js          verification codes: issue, verify, use up
  delivery.service.js      sends codes: email (Nodemailer), SMS (Twilio), console
  account.service.js       the signed-in account's profile
  idempotency.service.js   safe retries (operationId)
utils/                     validators, serializers, ApiError, withTransaction
database/
  schema.sql               tables (read the notes at the top)
  seed.sql                 reference data + demo students and staff
  seed-accounts.js         demo lecturer login (needs bcrypt, so it is .js)
  add-staff.js             npm run staff:add
  ER_DIAGRAM.md            the tables as a diagram
scripts/email-test.js      npm run email:test
tests/                     Jest + Supertest; TEST_CASES.md explains each one
API_CONTRACT.md            every endpoint with real request/response examples
```

## 8. How it works

### Signing up with a verification code

Every sign-up is three calls (the Android app's `AuthService` has the same three
steps):

1. **request** — the server checks the person is on the list (student number +
   NRC + date of birth match a roster row, or email + employee ID match the
   staff list). If so it creates a code and **emails it with Nodemailer**. The
   reply only says *"sent to c\*\*\*@example.com"*; the code is never in any
   HTTP response, so the only way to get it is to read that mailbox.
2. **verify** — the person types the code.
3. **complete** — they choose a password; the account is created and they are
   signed in.

The rules, all enforced in the database rather than trusted to the app:

- a code lasts **10 minutes** and works **once**
- **5 tries** per code, counted with one atomic `UPDATE ... WHERE attempts < 5`,
  so even 50 guesses sent at the same instant get only 5 checks
- a new code can be asked for every **30 seconds**, and asking again kills the
  old one
- after a code is accepted there are **30 minutes** to finish
- only a keyed hash (HMAC-SHA256) of the code is stored, so a copy of the
  database does not reveal live codes
- if the email cannot be sent, the code is deleted and the app is told
  `DELIVERY_FAILED`, so nothing half-sent is left behind

The answers do not leak who is registered: a wrong NRC and an unknown student
number get the same `NOT_ON_ROSTER` reply, and *"forgot password"* always says
"sent", whether or not the email has an account.

### Signing in

Students sign in with their student number or email, lecturers with email.
Passwords are stored only as bcrypt hashes. A correct password gets a **JWT**,
a signed token the app sends with every request. 5 wrong passwords lock the
account for 15 minutes; resetting the password unlocks it and invalidates every
token issued before the reset.

### Roles are checked on the server

Every protected route checks the token, that the account is still active, and
the role. A student sending `GET /api/students` from Postman is refused even
though the app never shows them that screen. `GET /api/students/me` takes the
student's id from the token, never from the URL, so nobody can read someone
else's record by changing an id.

### The 15-seat limit

Counting the members and then adding one is not safe: two requests can both
count 14 before either writes, and the group ends up with 16. Every path that
can put a student in a group — assigning, creating a student straight into a
group, an approved change request, the offline-sync operations — goes through
one function, `lockAndCheckSeat` in `services/group.service.js`. It locks the
group row (`SELECT ... FOR UPDATE`) inside a transaction before counting, so a
second request waits until the first has finished and then sees the true count.
The tests fire up to 10 requests at the same moment for the last seat and check
exactly one wins.

### Change requests

A student can ask for a group change or a number correction; nothing changes
until a lecturer approves. Approval and the change happen in one transaction,
with the request locked first, so two lecturers approving at once apply it
once, and a group that filled up in the meantime leaves the request pending.
Only one request of each kind can be open per student; the database enforces
that with a unique key, so even five taps at once create one request.

### Soft delete

Deleting a student sets `deleted_at`, frees their seat and disables their
login. The row stays, so their student number and NRC can never be reused for
someone else.

### Safe retries

When the app replays a change after a lost response, it sends the same
`operationId`. The server recognises it and returns the stored answer without
doing the work again. The same id with different content is refused.

## 9. Why the database looks the way it does

**Every fact is stored once.** This version was restructured to remove
duplicate data and duplicate code:

| Before | Now |
|---|---|
| `students.active` stored next to `deleted_at` (two columns that could disagree) | `active` is **computed** from `deleted_at` by MySQL (a generated column); code only sets `deleted_at` |
| `accounts.role` stored next to `student_id` / `staff_id` | `role` is **computed** from which link is set; a CHECK guarantees exactly one |
| Two request tables with the same status / reason / resolved-by columns and two copies of the code | One `change_requests` table with a `kind` column, one service |
| "One open request" checked with a SELECT that two taps could race past | A UNIQUE key on a computed `is_pending` column |
| Programme and group codes hard-coded in the validation code as well as in the database | Codes live only in `programmes` / `lab_groups`; adding group G05 is one `INSERT`, no code change |
| The same student query written three times; the offline-sync copy had drifted (it skipped the name check, and its delete was not one transaction) | One `student.service.js` used by every route and every offline operation |
| begin / commit / rollback / release repeated in eight places | One `withTransaction()` helper |
| Two near-identical "finish sign-up" handlers | One `createAccountFromCode()` |
| `verification_codes` could hold a code linked to the wrong kind of person | A CHECK ties each purpose to exactly the link it needs |

Two copies are kept on purpose, because they are history, not duplicates:
`change_requests.from_group_id` / `old_number` record what the student had when
they asked (after approval the student row no longer shows it), and a
lecturer's login email starts as a copy of their staff-list email (the staff
list says who *may* sign up, the account is the login, and every login email
must be unique across students and staff in one index).

**Why the NRC is not the primary key.** Every citizen's NRC is different, so it
is `UNIQUE` and `NOT NULL`: the database refuses a second student with the same
NRC, including a deleted one. But it is deliberately not the primary key:

- a primary key must never change, and an NRC can be typed wrongly and need
  correcting
- a primary key is copied into every table that points at the student, into
  URLs and into logs, which is the last place a national ID number belongs

So each student has a random `student_id` that never changes, and the NRC is a
unique, correctable field. Lists in the app only ever get a masked NRC
(`70****/**/1`).

## 10. Not in this backend

Room, WorkManager and the offline queue live in the Android app. The server
already has what they rely on: a `version` on every student row (to detect
conflicts) and `operation_receipts` (for safe retries).
