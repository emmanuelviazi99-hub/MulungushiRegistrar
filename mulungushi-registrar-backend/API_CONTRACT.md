# API contract

Base URL in development: `http://10.0.2.2:3000/api` from the Android emulator
(`10.0.2.2` is how the emulator reaches your laptop's `localhost`). Use HTTPS
on a real host for anything beyond local development.

Every response is JSON. Every error has this shape (some add one extra field,
noted below):

```json
{ "success": false, "error": "GROUP_FULL", "message": "This lab group already has 15 active students." }
```

Protected endpoints need the token from sign-in or sign-up:

```
Authorization: Bearer <token>
```

The examples below were captured from the running server.

| Method | Endpoint | Who | Purpose |
|---|---|---|---|
| POST | `/auth/register/request` | anyone | Student sign-up step 1: prove you are on the roster, get a code by email |
| POST | `/auth/register/verify` | anyone | Step 2: enter the code |
| POST | `/auth/register/complete` | anyone | Step 3: choose a password; account created, signed in |
| POST | `/auth/staff/request` | anyone | Lecturer sign-up step 1: email + employee ID on the staff list |
| POST | `/auth/staff/verify` | anyone | Step 2 |
| POST | `/auth/staff/complete` | anyone | Step 3 |
| POST | `/auth/password/request` | anyone | Forgotten password: email a code |
| POST | `/auth/password/verify` | anyone | Check the code (optional, lets the app show the password screen) |
| POST | `/auth/password/reset` | anyone | Code + new password |
| POST | `/auth/login` | anyone | Student number or email + password |
| POST | `/auth/logout` | signed in | The app discards its token |
| GET | `/students/me` | student | Own record, including own NRC and birth date |
| PUT | `/students/me` | student | Change own name / programme |
| GET | `/students` | lecturer | Search, filter, page through the roster |
| POST | `/students` | lecturer | Add a student to the roster |
| GET | `/students/:id` | lecturer | One student, full record |
| PUT | `/students/:id` | lecturer | Correct name, programme, NRC, birth date |
| DELETE | `/students/:id` | lecturer | Soft delete |
| POST | `/students/:id/group` | lecturer | Assign or move to a group (15-seat safe) |
| POST | `/students/mutate` | signed in | Replay an offline change (CREATE / EDIT / DELETE / TRANSFER) |
| GET | `/groups` | signed in | Members per group |
| POST | `/group-requests` | student | Ask to change group |
| GET | `/group-requests` | lecturer | The queue |
| PUT | `/group-requests/:id/approve` | lecturer | Approve (re-checks the 15-seat limit) |
| PUT | `/group-requests/:id/decline` | lecturer | Decline |
| POST | `/number-correction-requests` | student | Ask to correct the student number |
| GET | `/number-correction-requests` | lecturer | The queue |
| PUT | `/number-correction-requests/:id/approve` | lecturer | Approve |
| PUT | `/number-correction-requests/:id/decline` | lecturer | Decline |

---

## Signing up with a verification code

The code looks like `MU-7K4QXP`. It is **emailed** (Nodemailer) and never
appears in any response. Rules: valid 10 minutes, single use, 5 tries, a new
code at most every 30 seconds (asking again cancels the old one), 30 minutes to
finish after verifying. Codes are case-insensitive and the `MU-` is optional
when typing.

### `POST /auth/register/request` (student)

```json
// Request — studentNumber + nrc + dateOfBirth must all match the roster row a lecturer created.
// nrc may be typed with or without slashes; phone accepts 0977…, 260977…, +260 977 …
{
  "fullName": "Chanda Mwansa",
  "studentNumber": "202301001",
  "nrc": "700001/10/1",
  "dateOfBirth": "2003-04-12",
  "email": "chanda@example.com",
  "phone": "0977123456"
}

// 200 — a code was emailed
{ "success": true, "status": "OK", "maskedEmail": "c***@example.com", "expiresInSeconds": 600, "resendAfterSeconds": 30 }
```

| Error | When |
|---|---|
| 404 `NOT_ON_ROSTER` | The details do not match a roster row. One answer for every mismatch, so it cannot be used to find out which student numbers exist |
| 409 `ALREADY_REGISTERED` | This student already has an account |
| 409 `EMAIL_IN_USE` | Another account uses that email |
| 429 `RESEND_TOO_SOON` | Asked again within 30 seconds. Extra field: `"retryAfterSeconds": 22` |
| 502 `DELIVERY_FAILED` | The email could not be sent. Nothing was stored; ask again |
| 422 `VALIDATION_ERROR` | A field is malformed (`message` names it) |

### `POST /auth/register/verify`

```json
// Request
{ "email": "chanda@example.com", "code": "MU-7K4QXP" }

// 200
{ "success": true, "status": "OK", "fullName": "Chanda Mwansa" }

// 400 — wrong code
{ "success": false, "error": "BAD_CODE", "message": "That code is not right.", "triesLeft": 4 }
```

Also: 429 `TOO_MANY_TRIES` (`"triesLeft": 0`, ask for a new code), 410
`CODE_EXPIRED` (expired, replaced, already used, or never asked for — the same
answer for all of them).

### `POST /auth/register/complete`

```json
// Request — programmeCode is optional (the roster value is the default)
{ "email": "chanda@example.com", "password": "Str0ngPass!", "programmeCode": "CS" }

// 201 — signed in
{
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiIs…",
  "account": {
    "accountId": 1, "role": "STUDENT", "email": "chanda@example.com", "phone": "+260977123456",
    "displayName": "Chanda Mwansa", "studentId": "9d0684ac-c5b1-11f1-a0d1-02fc00000001", "employeeId": null
  },
  "student": {
    "studentId": "9d0684ac-c5b1-11f1-a0d1-02fc00000001", "studentNumber": "202301001", "fullName": "Chanda Mwansa",
    "nrcMasked": "70****/**/1", "nrc": "700001/10/1", "dateOfBirth": "2003-04-12",
    "programmeCode": "CS", "groupCode": "G01", "active": true, "version": 1,
    "createdAt": "2026-10-11T20:23:25.952Z", "updatedAt": "2026-10-11T20:23:25.952Z"
  }
}
```

Errors: 403 `NOT_VERIFIED` (no accepted code in the last 30 minutes, or it was
already used), 422 `VALIDATION_ERROR` (password must be 8–72 characters), 400
`INVALID_PROGRAMME`.

### `POST /auth/staff/request` → `/auth/staff/verify` → `/auth/staff/complete` (lecturer)

Same three steps. The email + employee ID must be on the staff list (added by
the server operator with `npm run staff:add`), and the code goes to that email.

```json
// request
{ "fullName": "Mutale Chilufya", "email": "m.chilufya@mulungushi.edu.zm", "employeeId": "EMP-1002", "phone": "" }
// 200 { "success": true, "status": "OK", "maskedEmail": "m***@mulungushi.edu.zm", "expiresInSeconds": 600, "resendAfterSeconds": 30 }

// verify
{ "email": "m.chilufya@mulungushi.edu.zm", "code": "MU-H3QW9Z" }
// 200 { "success": true, "status": "OK", "fullName": "Mutale Chilufya" }

// complete
{ "email": "m.chilufya@mulungushi.edu.zm", "password": "Lecturer#Pass1" }
// 201
{
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiIs…",
  "account": {
    "accountId": 2, "role": "LECTURER", "email": "m.chilufya@mulungushi.edu.zm", "phone": null,
    "displayName": "Mutale Chilufya", "studentId": null, "employeeId": "EMP-1002"
  }
}
```

`phone` is optional for lecturers. `NOT_ON_ROSTER` here means the email and
employee ID are not on the staff list.

### Forgotten password

```json
// POST /auth/password/request
{ "email": "chanda@example.com" }
// 200 — ALWAYS this answer, whether or not the email has an account (a code is only sent if it does)
{ "success": true, "status": "OK", "maskedEmail": "c***@example.com", "expiresInSeconds": 600, "resendAfterSeconds": 30 }

// POST /auth/password/verify  (optional check before showing the new-password screen)
{ "email": "chanda@example.com", "code": "MU-PQ7RT2" }
// 200 { "success": true, "status": "OK" }

// POST /auth/password/reset
{ "email": "chanda@example.com", "code": "MU-PQ7RT2", "newPassword": "N3wPassword!" }
// 200 { "success": true, "status": "OK" }
```

A reset also unlocks the account and makes every token issued before it stop
working. Same code errors as above.

### `POST /auth/login`

```json
// Request — loginKey is a 9-digit student number or an email
{ "loginKey": "202301001", "password": "Str0ngPass!" }

// 200 — same body as register/complete (student) or staff/complete (lecturer)

// 401 — one answer for unknown user, wrong password, locked or disabled account
{ "success": false, "error": "INVALID_CREDENTIALS",
  "message": "Incorrect student number/email or password. After 5 wrong attempts the account is locked for 15 minutes." }
```

### `POST /auth/logout`
`200 { "success": true, "status": "OK" }`. Tokens are not stored on the server;
the app deletes its copy.

---

## Student record

A student record looks like this. **Lists** carry only `nrcMasked`; **single
records** (`/students/me`, `/students/:id`, create, update, sign-in) also carry
`nrc` and `dateOfBirth`.

```json
{
  "studentId": "47fa13b6-278f-4000-acba-33e383519c86",
  "studentNumber": "202301050",
  "fullName": "Ngosa Kabwe",
  "nrcMasked": "70****/**/1",
  "nrc": "700050/10/1",
  "dateOfBirth": "2003-05-17",
  "programmeCode": "IT",
  "groupCode": "G02",
  "active": true,
  "version": 1,
  "createdAt": "2026-10-11T20:23:26.290Z",
  "updatedAt": "2026-10-11T20:23:26.290Z"
}
```

`groupCode: null` means Unassigned. `version` goes up on every change; send it
back on an edit to detect that someone else changed the record first.

### `GET /students/me` (student) → `{ "success": true, "student": { … } }`

### `PUT /students/me` (student)
```json
{ "fullName": "Chanda M. Mwansa", "programmeCode": "IT" }   // either field
```
NRC, birth date and student number in the body are ignored: a student cannot
change the details that prove who they are. The number changes only through a
number-correction request.

### `GET /students?search=&programme=&group=&page=&limit=` (lecturer)
A 9-digit `search` is an exact student-number match; anything else matches part
of the name. `group=UNASSIGNED` lists students with no group. `limit` max 100.

```json
{
  "success": true,
  "students": [ { "studentId": "…", "studentNumber": "202301014", "fullName": "Alick Mwale", "nrcMasked": "70****/**/1",
                  "programmeCode": "IT", "groupCode": "G01", "active": true, "version": 1, "createdAt": "…", "updatedAt": "…" } ],
  "page": 1, "limit": 2, "total": 14, "totalPages": 7
}
```

### `POST /students` (lecturer)
```json
{ "fullName": "Ngosa Kabwe", "studentNumber": "202301050", "nrc": "700050/10/1",
  "dateOfBirth": "2003-05-17", "programmeCode": "IT", "groupCode": "G02" }
// 201 { "success": true, "student": { …full record… } }
```
`groupCode` is optional (omit for Unassigned). This does not create a login;
the student signs up themselves with the number, NRC and birth date entered
here.

Errors: 409 `DUPLICATE_STUDENT_NUMBER`, 409 `DUPLICATE_NRC` (also for deleted
students, whose number and NRC stay reserved), 409 `GROUP_FULL`, 400
`INVALID_PROGRAMME`, 400 `INVALID_GROUP`, 422 `VALIDATION_ERROR`.

### `GET /students/:id` (lecturer) → full record, or 404 `STUDENT_NOT_FOUND`

### `PUT /students/:id` (lecturer)
```json
{ "fullName": "Ngosa M. Kabwe", "nrc": "700050/10/2", "dateOfBirth": "2003-05-18", "version": 1 }  // any of these
```
409 `VERSION_CONFLICT` if `version` no longer matches; 409 `DUPLICATE_NRC`.

### `DELETE /students/:id` (lecturer)
`200 { "success": true, "message": "Student soft-deleted." }`. Frees the seat
and disables the login. A second call gives 404.

### `POST /students/:id/group` (lecturer)
```json
{ "groupCode": "G03", "operationId": "6a1f6f0e-3c2b-4d0e-9a59-0d4b1f7e2c11", "version": 1 }
// 200 { "success": true, "student": { …, "groupCode": "G03", "version": 2 } }
```
`groupCode` may be `UNASSIGNED`. `operationId` (a UUID) makes a retry safe;
`version` is optional. Errors: 409 `GROUP_FULL` (the student stays where they
were), 409 `VERSION_CONFLICT`, 422 `OPERATION_ID_REUSED` (same id, different
body).

### `POST /students/mutate` — offline changes

Each queued change is sent with its own `operationId`. Resending the same one
returns the stored answer without applying the change again.

```json
{ "operationId": "9b2e2c55-1d7a-4f43-8a52-5f1f0e6c2d10", "type": "EDIT",
  "studentId": "47fa13b6-…", "baseVersion": 3, "payload": { "fullName": "Ngosa M. Kabwe" } }

// 200
{ "success": true, "status": "APPLIED", "record": { …full student record, or null after DELETE… } }
```

| type | who | payload | same rules as |
|---|---|---|---|
| `CREATE` | lecturer | the `POST /students` body | `POST /students` |
| `EDIT` | lecturer (any `studentId`) or student (always themselves) | fields of `PUT /students/:id` | `PUT /students/:id`, `PUT /students/me` |
| `DELETE` | lecturer | — | `DELETE /students/:id` |
| `TRANSFER` | lecturer | `{ "groupCode": "G02" }` | `POST /students/:id/group` |

`baseVersion` is the version the phone last saw; if the server has moved on the
answer is 409 `VERSION_CONFLICT`, for the app to show as a conflict.

---

## Groups

### `GET /groups`
```json
{ "success": true, "groups": [
  { "groupCode": "G01", "capacity": 15, "activeCount": 14, "full": false },
  { "groupCode": "G02", "capacity": 15, "activeCount": 2, "full": false } ] }
```

---

## Change requests

Nothing changes until a lecturer approves. A student can have one open request
of each kind at a time (409 `REQUEST_ALREADY_PENDING`).

### `POST /group-requests` (student)
```json
{ "groupCode": "G02", "reason": "Timetable clash" }      // groupCode may be UNASSIGNED
// 201 { "success": true, "requestId": 1, "status": "PENDING" }
```

### `POST /number-correction-requests` (student)
```json
{ "newNumber": "202301777", "reason": "Typo" }
// 201 { "success": true, "requestId": 2, "status": "PENDING" }
```

### `GET /group-requests?status=PENDING` and `GET /number-correction-requests?status=PENDING` (lecturer)
`status` is `PENDING` (default), `APPROVED` or `DECLINED`. Oldest first.

```json
{ "success": true, "requests": [
  { "requestId": 1, "kind": "GROUP", "studentId": "9d0684ac-…", "studentName": "Chanda Mwansa", "studentNumber": "202301001",
    "fromGroupCode": "G01", "toGroupCode": "G02", "reason": "Timetable clash",
    "status": "PENDING", "requestedAt": "2026-10-11T20:23:26.338Z", "resolvedAt": null } ] }

{ "success": true, "requests": [
  { "requestId": 2, "kind": "NUMBER", "studentId": "9d0684ac-…", "studentName": "Chanda Mwansa", "studentNumber": "202301001",
    "oldNumber": "202301001", "newNumber": "202301777", "reason": "Typo",
    "status": "PENDING", "requestedAt": "2026-10-11T20:23:26.347Z", "resolvedAt": null } ] }
```

`fromGroupCode` / `oldNumber` are what the student had when they asked, and
stay that way after approval.

### `PUT /…/:id/approve` and `PUT /…/:id/decline` (lecturer)
`200 { "success": true, "status": "APPROVED" }` (or `"DECLINED"`).

Approving re-checks everything at that moment, and on any failure the request
stays `PENDING` and the student is unchanged: 409 `GROUP_FULL`, 409
`DUPLICATE_STUDENT_NUMBER`, 404 `STUDENT_NOT_FOUND` (deleted since). 404
`REQUEST_NOT_FOUND` if it is not pending, or the id belongs to the other kind.

---

## Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 422 | A field failed validation; `message` starts with the field name |
| `INVALID_PROGRAMME` / `INVALID_GROUP` | 400 | No programme / lab group with that code |
| `NOT_ON_ROSTER` | 404 | Sign-up details do not match the roster / staff list |
| `ALREADY_REGISTERED` | 409 | This person already has an account |
| `EMAIL_IN_USE` | 409 | Another account uses that email |
| `BAD_CODE` | 400 | Wrong verification code; `triesLeft` says how many remain |
| `TOO_MANY_TRIES` | 429 | 5 wrong tries; ask for a new code |
| `CODE_EXPIRED` | 410 | No live code (expired, replaced, used, or never sent) |
| `RESEND_TOO_SOON` | 429 | Wait `retryAfterSeconds` before asking again |
| `NOT_VERIFIED` | 403 | Finish step 2 first (or the 30 minutes ran out) |
| `DELIVERY_FAILED` | 502 | The code could not be emailed; try again |
| `RATE_LIMITED` | 429 | Too many requests from this address; wait a few minutes |
| `INVALID_CREDENTIALS` | 401 | Sign-in failed (one answer for every reason) |
| `UNAUTHORIZED` | 401 | Missing, invalid or expired token, or a token from before a password reset |
| `FORBIDDEN` | 403 | Signed in, but this is not allowed for your role |
| `STUDENT_NOT_FOUND` | 404 | No active student with that id |
| `REQUEST_NOT_FOUND` | 404 | No pending request with that id (of that kind) |
| `NOT_FOUND` | 404 | No such endpoint |
| `DUPLICATE_STUDENT_NUMBER` | 409 | Number already in use (including by a deleted student) |
| `DUPLICATE_NRC` | 409 | NRC already in use (including by a deleted student) |
| `GROUP_FULL` | 409 | The group already has 15 active students |
| `VERSION_CONFLICT` | 409 | The record changed since you last read it |
| `REQUEST_ALREADY_PENDING` | 409 | Already an open request of that kind |
| `CONFLICT` | 409 | Same `operationId` still being processed, or another clash |
| `OPERATION_ID_REUSED` | 422 | Same `operationId` with a different body |
| `INTERNAL_ERROR` | 500 | Unexpected server error (details only in the server log) |
