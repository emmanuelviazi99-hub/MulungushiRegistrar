# Mulungushi Registrar — Backend

Node.js + Express + MySQL backend for the ICT361 Student Registration and
Lab Group Management system. This is the **remote backend only** (Phase 1).
Room, WorkManager and offline sync live in the Android app and are a
separate, later phase — see the note at the bottom of this file.

## 1. Requirements

- Node.js 18+
- MySQL 8+ (InnoDB, which is the default engine)

## 2. Setup

```bash
cd backend
npm install
cp .env.example .env        # then edit .env with your real DB credentials
```

Generate a real JWT secret and put it in `.env`:
```bash
node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"
```

Create the database and load the schema + reference/demo data:
```bash
mysql -u root -p -e "CREATE DATABASE mulungushi_registrar CHARACTER SET utf8mb4;"
mysql -u root -p mulungushi_registrar < database/schema.sql
mysql -u root -p mulungushi_registrar < database/seed.sql
npm run seed:accounts        # creates the lecturer login (bcrypt-hashed — see below)
```

Start the server:
```bash
npm start          # or: npm run dev   (restarts on file changes)
```

Check it's alive:
```bash
curl http://localhost:3000/health
```

### Demo accounts after seeding

| Role | Username | Password | Notes |
|---|---|---|---|
| Lecturer | `lecturer1` | `Lecturer#2025` | From `SEED_LECTURER_*` in `.env` |
| Student | *(register with a claim code)* | — | See `database/seed.sql` for ready-made claim codes, e.g. student number `202301001` + code `MU-7K4Q` |

Change `SEED_LECTURER_PASSWORD` in `.env` before anyone outside your group sees this.

### Connecting from the Android emulator

The emulator can't reach `localhost` on your laptop directly — use
`10.0.2.2` instead, e.g. `http://10.0.2.2:3000/api/...`. A physical phone
on the same Wi-Fi network instead uses your laptop's LAN IP address. Either
way, switch to a real HTTPS host before the final submission (the brief
requires HTTPS for the remote backend).

## 3. Running the tests

```bash
# in .env, point TEST_DB_NAME at a database that is safe to drop/recreate
npm test
```

`tests/helpers/resetDb.js` drops and rebuilds that test database from
`schema.sql` + `seed.sql` before the suite runs, so tests always start from
the same known state and never touch your real data. See
`tests/TEST_CASES.md` for what each test demonstrates and how it maps to
the brief's checklist.

## 4. Project structure

```
backend/
├── server.js              # wires everything together, starts listening
├── config/database.js     # one shared MySQL connection pool
├── middleware/
│   ├── auth.js             # verifies the JWT, loads req.user
│   ├── role.js              # rejects requests from the wrong role
│   ├── validation.js       # field-level rules (express-validator)
│   └── errorHandler.js     # turns any thrown error into the standard JSON shape
├── routes/                 # URL -> controller wiring, one file per resource
├── controllers/            # request in, response out; thin — delegates to services
├── services/
│   ├── claim.service.js    # claim-code generation/verification
│   ├── group.service.js    # the capacity-safe group assignment transaction
│   └── idempotency.service.js  # safe-retry support (operationId)
├── utils/                  # ApiError, asyncHandler, response serializers
├── database/
│   ├── schema.sql           # table definitions
│   ├── seed.sql              # reference data + fictitious demo students
│   ├── seed-accounts.js     # creates the lecturer login (needs bcrypt, hence .js not .sql)
│   └── ER_DIAGRAM.md         # Mermaid ER diagram
├── tests/                   # Jest + Supertest, see TEST_CASES.md
└── API_CONTRACT.md          # full endpoint list with example requests/responses
```

## 5. How it works, in plain English

**`server.js`** creates the Express app, tells it to parse JSON bodies,
registers each group of routes under its URL prefix (`/api/auth`,
`/api/students`, ...), and finally registers the error handler. Middleware
and routes are matched top-to-bottom, which is why the error handler must
be added last — it only catches what nothing above it handled.

**The database connection** (`config/database.js`) is a *pool*, not a
single connection — think of it as a small stack of phone lines into
MySQL. When one request needs the database, it borrows a line, uses it,
and gives it back; several requests can be in progress at once without
waiting on each other.

**A primary key** uniquely identifies one row in a table and never
changes — `students.student_id` is deliberately a random UUID rather than
the student's number, precisely so that correcting a mistyped number later
never changes which row "is" that student. **A foreign key** is a column
that points at another table's primary key, and the database itself
refuses to let it point at something that doesn't exist — e.g. you cannot
insert a student with a `programme_id` that isn't in the `programmes`
table.

**Claim codes** exist because a lecturer typically creates the student
record first (name, number, programme) and the student later proves on
their phone that the record is theirs. The code is generated once,
e-mailed/handed to the student, and the server stores only its SHA-256
hash — like a password, the plain code never sits in the database. The
student sends the number + code back; if they match, the server lets them
finish registering, and crucially *links* the new login to the *existing*
student row instead of creating a second one. That link is enforced by the
database too: `accounts.student_id` has a `UNIQUE` constraint, so two
accounts can never point at the same student.

**Registration** (`POST /api/auth/register`) re-checks the claim code (it
never trusts that the app already checked), hashes the chosen password
with bcrypt, and — inside one transaction — creates the account row and
marks the claim code used together. If either step failed partway, the
transaction rolls back and nothing is left half-done.

**Login** (`POST /api/auth/login`) checks the password with
`bcrypt.compare` (which re-hashes the attempt and compares hashes — the
plain password is never stored anywhere, including not in memory for long)
and, if it matches, signs a **JWT**: a signed, expiring token containing
the account's id and role. The server's secret key signs it; anyone who
has the token can prove to the server who they are by presenting it,
without the server having to remember anything about open sessions.

**Every protected route** first runs `requireAuth` (checks the JWT is
valid *and* that the account is still active in the database — a token
doesn't keep working after an account is disabled) and then, for routes
that only one role should reach, `requireRole('LECTURER')` or
`requireRole('STUDENT')`. This is why a student sending
`GET /api/students` straight from a tool like Postman still gets rejected
even though that button doesn't exist in their part of the app — the
server checks the role itself, it doesn't trust the client to have hidden
the option.

**Student ownership**: `GET /api/students/me` never takes an id from the
URL — it always uses `req.user.studentId`, which came from the verified
token, not from anything the client typed. A student literally cannot ask
for someone else's record through this endpoint, because the endpoint
never looks at an id the client supplied.

**The 15-seat limit** (`services/group.service.js`) is the trickiest part.
Naively, you'd count how many students are in a group and, if it's under
15, add one more. The problem is that two requests can both do the count
before either does the write — both see 14, both think there's room, both
write, and now there are 16. The fix is a MySQL transaction that takes a
row lock (`SELECT ... FOR UPDATE`) on the group before counting: a second
transaction that wants the same lock has to wait until the first one
finishes (commits or rolls back), so the count the second transaction
eventually sees is never stale. The full reasoning, with more detail, is
in the comment at the top of that file.

**Soft deletion**: `DELETE /api/students/:id` never removes a row — it
sets `active = 0` and `deleted_at`, disables the linked login, and frees
the group seat. The `student_number` stays `UNIQUE` across deleted rows
too, so the same number can't be accidentally reused for a different
person.

**Idempotent retries** (`services/idempotency.service.js`) solve the case
where a mutating request (like a group assignment) succeeds on the server
but the response never reaches the phone — maybe the connection dropped.
If the client always sends the same `operationId` when it retries the same
logical action, the server recognises the id, sees it already has a
result, and sends that same result back **without doing the work again**.
If the client reuses an id for a *different* request body, that's
treated as a bug, not a legitimate retry, and rejected.

**How Android will talk to this**: the app never touches MySQL directly.
It makes HTTPS requests to these endpoints (via Retrofit, once that layer
is built), attaches the JWT it got from `/auth/login` on every
subsequent call, and reads the JSON back. `API_CONTRACT.md` has the exact
request/response shape for every endpoint.

## 6. Scope note — what's deliberately NOT here yet

This backend is Phase 1: a correct, secure, role-checked, capacity-safe
remote API. It intentionally does not include Android Room, WorkManager,
an offline operation queue, or client-side conflict resolution — those
belong in the Android app, not the server, and are Phase 2 of the overall
project. The server-side pieces that offline sync will lean on
(`version` on every student row, `operation_receipts` for idempotent
retries) are already in place so that Phase 2 doesn't require a schema
rework. Do not submit the project without that phase — the brief marks
offline storage, sync and conflict handling as 15 of 100 points, and
Challenges 2 and 3 can't be demonstrated without it.
