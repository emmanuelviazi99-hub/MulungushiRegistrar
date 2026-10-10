# API contract

Base URL (local dev): `http://10.0.2.2:3000/api` from the Android emulator
(`10.0.2.2` is how the emulator reaches your laptop's `localhost`). Use
HTTPS and a real host for anything beyond local development.

All responses are JSON. All error responses share this shape:

```json
{ "success": false, "error": "GROUP_FULL", "message": "This lab group already has 15 active students." }
```

All protected endpoints require:

```
Authorization: Bearer <token>
```

| Method | Endpoint | Role | Purpose |
|---|---|---|---|
| POST | `/auth/verify-claim` | Public | Check a student number + claim code pair |
| POST | `/auth/register` | Public | Create a student account, link it to an existing student |
| POST | `/auth/login` | Public | Log in, get a JWT |
| POST | `/auth/logout` | Student/Lecturer | Acknowledge logout (stateless JWT) |
| GET | `/students/me` | Student | Own profile |
| PUT | `/students/me` | Student | Edit own name/programme |
| GET | `/students` | Lecturer | Search/filter/paginate the roster |
| POST | `/students` | Lecturer | Add a student + generate a claim code |
| GET | `/students/:id` | Lecturer | One student's record |
| PUT | `/students/:id` | Lecturer | Edit a student's name/programme |
| DELETE | `/students/:id` | Lecturer | Soft-delete a student |
| POST | `/students/:id/group` | Lecturer | Assign/transfer a student (capacity-safe) |
| GET | `/groups` | Student/Lecturer | Active counts per group |
| POST | `/group-requests` | Student | Request a group change |
| GET | `/group-requests` | Lecturer | List group-change requests |
| PUT | `/group-requests/:id/approve` | Lecturer | Approve (re-checks capacity) |
| PUT | `/group-requests/:id/decline` | Lecturer | Decline |
| POST | `/number-correction-requests` | Student | Request a number correction |
| GET | `/number-correction-requests` | Lecturer | List correction requests |
| PUT | `/number-correction-requests/:id/approve` | Lecturer | Approve |
| PUT | `/number-correction-requests/:id/decline` | Lecturer | Decline |

---

## Auth

### `POST /auth/verify-claim`
```json
// Request
{ "studentNumber": "202301001", "claimCode": "MU-7K4Q" }

// 200
{ "success": true, "studentId": "6f1e...", "fullName": "Chanda Mwansa" }

// 401 INVALID_CLAIM_CODE / 409 CLAIM_ALREADY_USED / 410 CLAIM_EXPIRED / 409 ALREADY_REGISTERED
```

### `POST /auth/register`
```json
// Request
{ "studentNumber": "202301001", "claimCode": "MU-7K4Q", "username": "chanda.m", "password": "Str0ngPass!" }

// 201
{ "success": true, "token": "eyJ...", "accountId": 7, "username": "chanda.m", "role": "STUDENT", "studentId": "6f1e..." }
```

### `POST /auth/login`
```json
// Request
{ "username": "lecturer1", "password": "Lecturer#2025" }

// 200
{ "success": true, "token": "eyJ...", "accountId": 1, "username": "lecturer1", "role": "LECTURER", "studentId": null }

// 401 INVALID_CREDENTIALS
```

---

## Student self-service

### `GET /students/me`
```json
{
  "success": true,
  "student": {
    "studentId": "6f1e...",
    "studentNumber": "202301001",
    "fullName": "Chanda Mwansa",
    "programmeCode": "CS",
    "groupCode": "G01",
    "active": true,
    "version": 3,
    "createdAt": "2026-09-01T08:00:00.000Z",
    "updatedAt": "2026-10-01T09:12:00.000Z"
  }
}
```

### `PUT /students/me`
```json
// Request (either field optional)
{ "fullName": "Chanda M. Mwansa", "programmeCode": "IT" }

// 200 -> same shape as GET /students/me
```

---

## Lecturer student management

### `GET /students?search=&programme=&group=&page=&limit=`
`search` matches a 9-digit value exactly against `studentNumber`, otherwise
does a partial match on `fullName`. `group=UNASSIGNED` lists students with
no group.

```json
{
  "success": true,
  "students": [ { "studentId": "...", "studentNumber": "202301001", "fullName": "Chanda Mwansa", "programmeCode": "CS", "groupCode": "G01", "active": true, "version": 3 } ],
  "page": 1, "limit": 20, "total": 18, "totalPages": 1
}
```

### `POST /students`
```json
// Request
{ "fullName": "New Student", "studentNumber": "202301099", "programmeCode": "CS", "groupCode": "G02" }

// 201
{
  "success": true,
  "student": { "studentId": "...", "studentNumber": "202301099", "fullName": "New Student", "programmeCode": "CS", "groupCode": "G02", "active": true, "version": 1 },
  "claimCode": "MU-4Q9Z"
}

// 409 DUPLICATE_STUDENT_NUMBER / 400 INVALID_PROGRAMME / 400 INVALID_GROUP / 409 GROUP_FULL
```

### `PUT /students/:id`
```json
// Request (version optional but recommended — enables optimistic-concurrency conflict detection)
{ "fullName": "Corrected Name", "version": 3 }

// 200 -> updated student
// 409 VERSION_CONFLICT if the row moved on since you read it
```

### `DELETE /students/:id`
```json
// 200
{ "success": true, "message": "Student soft-deleted." }

// Calling it again -> 404 STUDENT_NOT_FOUND (soft delete releases the seat exactly once)
```

### `POST /students/:id/group`
```json
// Request — operationId is optional but makes the retry safe (Challenge 2)
{ "groupCode": "G01", "operationId": "b3f1b7b0-...-...", "version": 4 }

// 200
{ "success": true, "student": { "...": "...", "groupCode": "G01", "version": 5 } }

// 409 GROUP_FULL — student's previous group is untouched
// 409 VERSION_CONFLICT — someone else edited this student first, reload and retry
// 422 OPERATION_ID_REUSED — same operationId sent with a different body
```

---

## Group totals

### `GET /groups`
```json
{
  "success": true,
  "groups": [
    { "groupCode": "G01", "capacity": 15, "activeCount": 14, "full": false },
    { "groupCode": "G02", "capacity": 15, "activeCount": 3, "full": false }
  ]
}
```

---

## Group-change requests

### `POST /group-requests` (student)
```json
{ "groupCode": "G03", "reason": "Clashes with my timetable" }
// 201 { "success": true, "requestId": 12, "status": "PENDING" }
```

### `GET /group-requests?status=PENDING` (lecturer)
```json
{
  "success": true,
  "requests": [
    { "requestId": 12, "studentId": "...", "studentName": "Chanda Mwansa", "studentNumber": "202301001",
      "fromGroupCode": "G01", "toGroupCode": "G03", "reason": "Clashes with my timetable",
      "status": "PENDING", "requestedAt": "2026-10-01T09:00:00.000Z", "resolvedAt": null }
  ]
}
```

### `PUT /group-requests/:id/approve` / `/decline` (lecturer)
```json
// 200 { "success": true, "status": "APPROVED" }
// approve re-checks capacity -> 409 GROUP_FULL if it filled up since the request was made
```

---

## Number-correction requests

### `POST /number-correction-requests` (student)
```json
{ "newNumber": "202301100", "reason": "Two digits were swapped at registration" }
// 201 { "success": true, "requestId": 7, "status": "PENDING" }
```

### `PUT /number-correction-requests/:id/approve` (lecturer)
```json
// 200 { "success": true, "status": "APPROVED" }
// 409 DUPLICATE_STUDENT_NUMBER if the new number is already taken by then
```

---

## Error codes reference

| Code | HTTP | Meaning |
|---|---|---|
| VALIDATION_ERROR | 422 | A field failed server-side validation |
| INVALID_CREDENTIALS | 401 | Login failed |
| UNAUTHORIZED | 401 | Missing/invalid/expired token |
| FORBIDDEN | 403 | Authenticated, but wrong role for this endpoint |
| STUDENT_NOT_FOUND | 404 | No active student matches |
| REQUEST_NOT_FOUND | 404 | No pending request with that id |
| NOT_FOUND | 404 | No such route |
| DUPLICATE_STUDENT_NUMBER | 409 | Number already in use |
| REQUEST_ALREADY_PENDING | 409 | Student already has an open request of that kind |
| GROUP_FULL | 409 | Target group already has 15 active students |
| VERSION_CONFLICT | 409 | Row changed since the client last read it |
| ALREADY_REGISTERED | 409 | Student already has a linked account |
| USERNAME_TAKEN | 409 | Username already in use |
| CLAIM_ALREADY_USED | 409 | Claim code already consumed |
| OPERATION_ID_REUSED | 422 | Same operationId sent with a different request body |
| INVALID_CLAIM_CODE | 401 | Code/number pair doesn't match |
| CLAIM_EXPIRED | 410 | Code's TTL has passed |
| INVALID_PROGRAMME / INVALID_GROUP | 400 | Unknown reference-data code |
| INTERNAL_ERROR | 500 | Unexpected server error |
