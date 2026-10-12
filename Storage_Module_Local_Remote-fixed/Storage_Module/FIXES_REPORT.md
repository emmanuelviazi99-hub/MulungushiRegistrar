# Mulungushi Registrar — fixes report

Covers the three supplied projects: **backend** (`mulungushi-registrar-backend`), **frontend**
(`MulungushiRegistrar-Frontend`, Android/Java) and **storage module** (`Storage_Module`).

## 1. Errors found and corrected

### Registration, claim codes, API
| # | Problem found | Fix |
|---|---|---|
| 1 | Frontend `ApiAuthService` called a non-existent endpoint (`/auth/verify-claim`), sent the **phone number as the claim code**, and reported **success on every failure** (network errors, 4xx/5xx). `verify` and `complete` were stubs that always returned OK. | Rewritten: real calls to `/auth/register/request` and `/auth/register/verify` (and the real password-reset endpoints); every server error code is mapped to a status; no answer = `NETWORK_ERROR`, never OK. |
| 2 | Backend registration still used the old roster-proof flow (name + student number + **NRC + date of birth + phone**, then a **password** step). The migration file described the new email-claim design but `schema.sql`, code and tests had not been updated. | Registration is now exactly five details (first name, last name, student number, programme, email) → claim code **emailed** → verifying the code registers the student (claims the roster row with that number, or creates one). No password, no phone, no NRC. `/register/complete` removed. |
| 3 | Phone/SMS claim-code functionality throughout (Twilio channel, `phone` columns, `phone` validators, `phone` in API responses, Android phone/NRC fields and validators). | Removed everywhere: SMS channel (`CODE_DELIVERY=sms` is now refused at start-up), `accounts.phone`, `verification_codes.phone`, `phoneRule`, `isValidPhone`, Android `checkPhone/normalisePhone/checkNrc`, `NrcTextWatcher`, `Student.phone/nrc`. |
| 4 | Single `full_name` field; first and last name not separate. | `first_name` + `last_name` in DB (with `full_name` a derived column for display/search), API (`firstName`, `lastName`), Android model, local storage, sync payload, edit screens (student and lecturer). |
| 5 | Student number rule inconsistent: Android added extra "looks fake" rules (111111111 / 123456789 rejected); backend trimmed spaces and accepted JSON numbers (losing leading zeros). | One rule everywhere: **true only for a string of exactly nine digits**. `StudentValidator.isValidStudentNumber` (Android) and `isValidStudentNumber` (backend, used by registration, lecturer API, requests and sync). |
| 6 | `services/sync.service.js` called `v.isValidNamePart`, which did not exist → every sync upload crashed (500). | Added `isValidNamePart` (2–50 letters). |
| 7 | Sync route existed but was **never mounted**; mounting it after `/api/students` would also have been swallowed by `GET /:id`. | Mounted at `/api/students/sync` before the students router, with its own 2 MB body limit (other endpoints keep 50 kB). |
| 8 | Malformed JSON body returned 500; oversize body returned 500. | 400 `INVALID_JSON`, 413 `PAYLOAD_TOO_LARGE`. |
| 9 | Two phones uploading the same new student at once could deadlock in InnoDB → 500. | Deadlock/lock-timeout retry; the second upload is reported as `DUPLICATE_STUDENT_NUMBER`. |
| 10 | Android login sent `username` (backend expects `loginKey`) and read `role` from the wrong place; when the server was unreachable **any** student number signed in. | Sends `loginKey`, reads `account.role`, stores the server's student record locally; offline fallback limited to the demo lecturer or a student already saved on the phone. |
| 11 | `/api/health` missing (only `/health`). | Both answer. |
| 12 | Server started without `SYNC_KEY` (every sync would then be 401) and the shipped `.env` lacked the `SEED_LECTURER_*` values, so `npm run seed:accounts` failed. | Start-up check for `SYNC_KEY`; demo lecturer values added to `.env`. |

### Android build problems
| # | Problem | Fix |
|---|---|---|
| 13 | `app/src/main/java/java/zm/.../SplashActivity.java` declared package `java.zm...` (a prohibited package name — breaks the build). | Removed (stale duplicate of the real `SplashActivity`). |
| 14 | `app/src/main/res/res/layout/` — "res" is not a valid resource folder (resource merge error). | Removed (identical duplicate of `activity_student_home.xml`). |
| 15 | `app/app/...` — an older nested copy of the whole app (with phone-code code). Not compiled, but confusing. | Removed. |
| 16 | Storage module was not integrated: no `LocalStorage`, `SyncManager`, `RegistrarApplication` in the app; manifest had no `android:name`; the in-memory repository lost everything on restart. | Integrated (see section 2). |
| 17 | Existing unit test `LoginValidatorTest.lettersOrInnerSpacesAreNotDigits` was already failing against the original code. | Login rule fixed: input starting with a digit is a student number (letters/spaces/symbols → `NOT_DIGITS`); other text is a staff login. |

### Local storage, remote storage, sync
| # | Problem | Fix |
|---|---|---|
| 18 | Sync payload had a single `name`; the backend requires `firstName`/`lastName` → every record would be rejected. | Model and JSON use `firstName`/`lastName`; old saved data with `name` is split once on load. |
| 19 | Local saves used `apply()` (asynchronous). | `commit()` in one write: a registration is on disk before the next screen opens. |
| 20 | After a failed upload nothing retried until the network changed (a server that was down stayed unsynced). | Retry after 15 s, 30 s, 1 min, 2 min, then every 5 min while anything is waiting; immediate sync after registration and when the network returns. |
| 21 | Records refused by the server were silently ignored. | `rejected` is read and reported; refused records stay saved and marked as waiting. |
| 22 | The server tidies values (e.g. email in lower case), which made the phone think a synced record was still waiting, forever. | Emails stored lower-case on the phone; a server copy with the same `updatedAt` is treated as our own upload and adopted. |
| 23 | A server record could create a second local record with the same student number (e.g. demo seed vs a real registration). | One record per number: replaces a synced local record, never overwrites unsynced local changes. |
| 24 | Lecturer edit sheet rebuilt the student from scratch and dropped fields it doesn't show (email, registration state). | Starts from a copy of the record. |

## 2. Files changed and why

### Backend
| File | Change |
|---|---|
| `database/schema.sql` | `students`: `first_name`, `last_name`, derived `full_name`, `email` (unique), `registered_at`; NRC / birth date optional. `accounts`: no `phone`. `verification_codes`: no `phone`; `student_number` + `signup_details` for student codes. New `student_sync_records` table. |
| `database/seed.sql` | Seed names split into first/last. |
| `database/migrations/001_email_claim_registration.sql` | Adds `signup_details`, fixes NRC check for NULL; tested on a copy of the old schema with data — result identical to the fresh schema. |
| `utils/validators.js` | Strict nine-digit string rule, `isValidNamePart`; phone helpers removed. |
| `middleware/validation.js` | Five-field registration rules (no trimming of the number, must be text); first/last name rules for roster endpoints; phone rules removed. |
| `services/code.service.js` | Email-only codes; stores student number + registration details. |
| `services/delivery.service.js` | SMS/Twilio removed; email (or console in development) only. |
| `services/student.service.js` | First/last name; `checkRegistrable` and `registerStudent` (claim or create, locked). |
| `services/sync.service.js` | Deadlock retry; phone/password never stored. |
| `controllers/auth.controller.js` | New two-step student registration; phone removed from lecturer/reset flows. |
| `controllers/student.controller.js` | `firstName`/`lastName` on updates. |
| `routes/auth.routes.js` | `/register/complete` removed. |
| `server.js` | Sync route mounted (before `/api/students`), `/api/health`. |
| `middleware/errorHandler.js` | `INVALID_JSON`, `PAYLOAD_TOO_LARGE`. |
| `config/env.js` | Refuses `sms` channel and missing `SYNC_KEY`. |
| `utils/serialize.js` | `firstName`, `lastName`, `email`, `registered`; no `phone`. |
| `.env`, `.env.example` | `SYNC_KEY`; demo lecturer values; Twilio section removed. |
| `tests/*` | Auth tests rewritten for the new flow; first/last name everywhere; new `validators.test.js`; extra sync tests. |
| `README.md`, `API_CONTRACT.md`, `tests/TEST_CASES.md` | Updated to the new flow and sync API. |

### Frontend (Android)
| File | Change |
|---|---|
| `RegisterActivity.java`, `res/layout/activity_register.xml` | Two pages: the five fields, then the claim code ("Verify and register"). Number accepted only when `isValidStudentNumber` is true; clear errors; on success saves locally and starts sync. |
| `StudentValidator.java` | `isValidStudentNumber` Boolean; `checkNamePart`; no extra number rules. |
| `LoginValidator.java` | Uses the same nine-digit Boolean. |
| `ContactValidator.java` | Phone and NRC rules removed. |
| `AuthService.java`, `ApiAuthService.java`, `DemoAuthService.java` | New registration contract; real HTTP; correct error mapping. |
| `LoginViewModel.java`, `LoginActivity.java` | Correct login request/response; stores the student record; safe offline fallback. |
| `Student.java` | `firstName`, `lastName`, `registeredAt`, `updatedAt`; no phone/NRC. |
| `StudentRepository.java` | Storage-module version: persistent, sync-aware, `addRegistered`, `rememberSignedIn`, `isPendingSync`. |
| `LocalStorage.java`, `SyncManager.java`, `SyncMerge.java`, `ApiRemoteStorage.java`, `RemoteConfig.java`, `RegistrarApplication.java` | Storage module integrated (with the fixes in section 1). |
| `AndroidManifest.xml` | `android:name=".RegistrarApplication"`. |
| `EditDetailsActivity.java`, `EditStudentSheet.java` + layouts | Separate first/last name fields; nine-digit rule. |
| `StudentHomeActivity.java`, `SyncActivity.java` | Notices cleared persistently; real "waiting to sync" status. |
| `GroupChangeActivity`, `LecturerRosterActivity`, `MyGroupActivity`, `RequestsActivity`, `StudentAdapter` | Display `fullName()`. |
| `res/values/strings.xml` | New labels/errors; phone/NRC/password-step strings removed. |
| `ApiConfig.java`, `README-FRONTEND.txt` | How to set the server address. |
| Tests | `StudentValidatorTest` (Boolean), `LoginValidatorTest`, `ContactValidatorTest`, new `SyncMergeTest`, new `androidTest/LocalStorageTest`. |
| Removed | `NrcTextWatcher.java`, `java/java/...SplashActivity.java`, `res/res/`, `app/app/`. |

### Storage module
`android/*` replaced by the integrated versions; `server/syncCore.js`, `server.js`, `studentsRouter.js`, `test.js` use the same rules as the backend; `README.md` rewritten.

## 3. Test results

| Suite | Result |
|---|---|
| Backend `npm test` (Jest + Supertest, real MariaDB) | **162 passed, 0 failed** (8 files). Baseline before changes: 87 passed, 20 failed. |
| Android JVM unit tests (validators, sync merge) | **37 passed** (original code: 1 failing test). |
| End-to-end: the app's own Java code (ApiAuthService, StudentRepository, LocalStorage, SyncManager, ApiRemoteStorage, LoginViewModel) against the real running backend + MariaDB, Android framework replaced by small test stand-ins | **10 passed** |
| Storage module test server | **16/16 PASS** |
| Migration 001 on a copy of the old schema with data | Columns, checks and indexes identical to the fresh schema |

What the end-to-end run proved: registration saves the record locally at once and it reaches remote
storage automatically; invalid numbers sent straight to the API are refused by the server; wrong,
re-requested and expired claim codes are handled; already-registered numbers and emails in use are
refused; registration followed by loss of network keeps the record across an app restart and syncs
when the network returns; with the server stopped, a change stays saved and is uploaded by the
automatic retry after the server restarts, and earlier records are still on the server after the
restart; repeated and simultaneous uploads create no duplicates; a second phone receives the server's
records; sign-in works after setting a password with the emailed reset code; an unreachable server
is never reported as success.

Checklist from the brief: 1–18 covered by the tests above, with the limits below.

## 4. Not verified here / configuration you need

- **The Android app was not built or run on a device.** This environment has no Android SDK and
  cannot reach Google's Maven repository. Checks done instead: every Java file compiled against a
  generated `R` class with no errors on the app's own classes or resource ids; all XML parses and every
  `@string/@drawable/...` reference exists; the storage, sync, auth and login code compiled and ran
  against Android stand-ins; `RegisterActivity` type-checked against stand-ins matching the real
  Android/Material signatures. **Build it in Android Studio and run `LocalStorageTest` on a device.**
- **Real email delivery was not exercised with a real mailbox** (tests use the backend's built-in
  test SMTP server and console delivery). Fill in `SMTP_*` and set `CODE_DELIVERY=email`, then
  `npm run email:test -- you@example.com`.
- **`ApiConfig.BASE_URL` is `http://10.245.76.34:3000/api`** — correct only if that is your PC's
  current IPv4 address. Emulator: `http://10.0.2.2:3000/api`. Check `BASE_URL/health` in the phone's browser.
- **`SYNC_KEY`** in `.env` must equal `RemoteConfig.SYNC_KEY` (both `change-me`). Change both for anything real.
- **Backend `node_modules` is not included**: the supplied one contained Linux-only native binaries
  (`bcrypt`). Run `npm install` in the backend folder.
- **Existing database:** run `database/migrations/001_email_claim_registration.sql` (back up first);
  a fresh install uses `schema.sql` + `seed.sql`.
- **Registration needs a connection**: the claim code is emailed and checked by the server, so the
  code step cannot complete offline. Everything after a successful verification (saving, syncing,
  retrying) works offline as described.
- Self-registered students have no password; to sign in again on another device they use
  "Forgot password?" (emailed reset code).
- Edits a phone makes are synced to `student_sync_records`; they are not copied into the backend's
  `students` roster table (the existing two-store design).
- The backend allows 10 code requests per IP per 15 minutes (existing rule); the app shows the
  server's message when this is hit.
