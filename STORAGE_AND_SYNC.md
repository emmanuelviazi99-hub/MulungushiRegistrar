# Local + remote storage: how the app keeps working offline

The app saves everything it shows **on the phone** (a Room database) and
saves every change on the phone **before** sending it to the server. With no
network the app still opens, shows the last data, and accepts changes; they
upload by themselves (WorkManager) when the phone is back online, even if the
app was closed in between. The **server** (MySQL) stays the one shared copy:
it still checks every rule (15 seats, unique numbers, who may do what) when the
change arrives.

```
 screen ──change──▶ RosterApi ──1. save──▶ Room: pending_operations (the outbox)
                        │
                        ├──2. upload now──▶ SyncEngine ──Idempotency-Key──▶ backend ──▶ MySQL
                        │        │ no network?
                        │        └──▶ WorkManager (SyncWorker): uploads when online, retries 30 s, 1 min, 2 min …
                        │
                        └──3. reload ◀── server answer ──▶ Room: cached_students, cache_entries
                                │ server not reachable?
                                └──▶ the saved copy from Room, with waiting changes shown on top
```

## What is stored where

**On the phone** (`databases/registrar.db`, one database per phone; every row
belongs to one account, so people who share a phone never see each other's data):

| Table | What |
|---|---|
| `cached_students` | The roster as the server last sent it (lecturer) |
| `cache_entries` | Other saved answers: group totals, open and answered requests, a student's own record, requests and group members, and when each was saved |
| `pending_operations` | The outbox: each change made on this phone, in order, with its id. **WAITING** until the server accepts it, or **REFUSED** with the server's reason |

The database is left out of Android backups (`res/xml/*_rules.xml`), and
signing out deletes that account's rows.

**On the server** (MySQL): the real tables (`students`, `change_requests`, …)
plus `operation_receipts`, where the answer to each change id is kept so a
repeat is answered without doing the change again.

## The code

| File | Job |
|---|---|
| `RegistrarDatabase`, `RegistrarDao`, `*Entity` | The Room database: three tables and their queries |
| `RoomLocalStore` (phone) / `MemoryLocalStore` (tests) | The `LocalStore` interface: saved answers + outbox |
| `PendingOp` | One change: what, on which record, its id (UUID), its label ("Move Natasha Banda to G02") |
| `SyncEngine` | Uploads the outbox in order. Stops at the first "no network" so the order is kept; marks refusals; gives a student added offline their server id for later changes; sends a second offline edit with the version the first one produced |
| `Overlay` | Shows waiting changes on top of the saved server data, marked "Waiting to upload" |
| `SyncWorker` | WorkManager job: runs when the phone has a network connection, retries with back-off, survives the app being closed and the phone restarting |
| `RosterApi` | What the screens call. Read: server, else saved copy. Change: save in the outbox, upload, reload |
| `SyncActivity` | The Sync tab: what is saved, what is waiting, what was refused, **Sync now** |
| backend `middleware/idempotency.js` | Applies each change id once (`Idempotency-Key` header) |

## The rules

- **Saved before sent.** A change is written to `pending_operations` before any
  network call, so closing the app, losing the network or restarting the phone
  never loses it.
- **Exactly once.** The change id is sent as `Idempotency-Key` on every try. If
  the server saved it but the answer was lost, the next try gets the stored
  answer and nothing happens twice (backend tests: `idempotency.test.js`).
- **In order.** Changes go up oldest first; at "no network" the upload stops
  and later changes wait, so they never overtake an earlier one.
- **The server decides.** A refused change (group full, number taken) is not
  shown as if it happened. The Sync tab shows it with the server's reason, and
  the dashboard's **To review** tile counts it.
- **No silent overwrites.** Every edit carries the record's version. If someone
  else saved the student first, the server refuses with `VERSION_CONFLICT` and
  the app shows **both versions side by side** with **Keep mine** (sends your
  change again on top of theirs) or **Use theirs**. When it happens online, the
  edit sheet asks at once; when it happens to an offline change, the Sync tab
  does.
- **Clearly labelled.** Records changed on this phone but not uploaded say
  "Waiting to upload". When the server can't be reached, the screens say they
  are showing what was saved, and when.
- **One shared copy.** Nothing is synced around the server: the saved data on
  each phone is only a copy of the server's answer, replaced at every reload.

## Staying up to date

- **While a screen is open** it reloads every 30 seconds (`LiveRefresh`), so a
  lecturer's approval appears on the student's home screen by itself.
- **Pull down** on the roster, requests, home, My group or Sync screen to reload
  now (`PullToRefresh`).
- **Network back:** the amber strip says "Back online. Syncing." and waiting
  changes upload at once. The strip also appears when the phone has a network
  but the server can't be reached, saying when the data on screen was saved.
- **Notifications with the app closed:** a student who taps **Turn on** when
  offered alerts gets an Android notification when a lecturer answers their
  request: `AnswerCheckWorker` (WorkManager) checks every 15 minutes, the
  shortest interval Android allows for background work. Each answer is announced
  once. The choice is remembered on the phone and cleared at sign-out.

## Show it on a phone (for the video / report)

Sign in as the lecturer with the backend running, open the roster once (it is
now saved on the phone), then make the server unreachable:

- **Phone on a USB cable** (`127.0.0.1` + `adb reverse`): stop the backend with
  **Ctrl+C**. Airplane mode does **not** cut a USB connection.
- **Phone or emulator on Wi-Fi** (`10.0.2.2` or the laptop's IP): airplane mode
  works, or stop the backend.

| # | Do | Expect |
|---|---|---|
| 1 | Server unreachable. Edit a student (new group) and add a student | "Saved on this phone. It will upload when you're back online." Both rows show **Waiting to upload**. Sync tab: 2 waiting |
| 2 | Swipe the app away, open it again (still unreachable) | Same roster, both changes still there and labelled; a message says it is showing what was saved, and when |
| 3 | Android Studio: **App Inspection → Database Inspector** → `registrar.db` → `pending_operations` | The two changes, in order, state `WAITING` (good evidence for the report) |
| 4 | **App Inspection → Background Task Inspector** | `SyncWorker` enqueued: waiting for a network connection (airplane mode) or retrying after 30 s, 1 min, 2 min … (backend stopped) |
| 5 | Make the server reachable again (`npm start`, or airplane mode off) without opening the app | WorkManager uploads at its next try (with airplane mode, as soon as the network is back). Opening the app or **Sync now** uploads at once. Labels gone; MySQL Workbench shows the changes (`SELECT * FROM students …`) |
| 6 | Tap **Sync now** a few times | Nothing changes twice: one new student, one move (`operation_receipts` has one row per change) |
| 7 | Conflict: server unreachable, rename a student. Start the server again. Before the phone uploads, on another device (or Postman with the lecturer token) change the same student's programme. Then open the Sync tab | "Changed by someone else" card: your change next to the server's. **Keep mine** saves your name on top of their programme; **Use theirs** drops yours |
| 8 | Refusal: server unreachable, add a student with a number that already exists. Server back, Sync now | Sync tab: "Not saved: Add …" with the server's reason. **OK** removes it. The dashboard's **To review** tile showed 1 |
| 9 | Sign out with a change still waiting | The sign-out sheet warns that it hasn't uploaded and that signing out deletes it |

Students: the same with **Request group change** or **Edit my details** while
the server is unreachable: the home screen shows "1 change waiting to upload", the Sync tab
lists it, and it uploads when the phone is online.

## Tests

- **Backend** (`npm test`): 119 tests, including `idempotency.test.js` (13): every change the app sends is applied once, ten copies at the same instant included.
- **App unit tests** (`app/src/test`, run in Android Studio): `OfflineSyncTest` (13): order, retries with the same key, refusals kept with the reason, server trouble or an expired sign-in stops without reordering, a student added offline then edited gets the server's id and version, each account uploads only its own changes, and how waiting changes are shown.
- **Live check** (the app's own Java classes against the running backend): 56 checks: offline edits and adds, "restart" with the same saved data, reconnect and upload once, a conflict with Keep mine, a refusal, a student's offline request and edit, an expired sign-in losing nothing, the background answer check announcing each answer once, sign-out clearing the phone.

## Known limits

- The saved copy is as fresh as the last time the phone reached the server;
  the screens say when that was.
- A change made offline is checked by the server only when it arrives, so a
  group can fill up in the meantime. The person is then told, and nothing is
  overwritten.
- Room and WorkManager are standard Android libraries that Gradle downloads on
  the first build.

The folder `Storage_Module_Local_Remote-fixed/` is the earlier stand-alone
storage module (a separate file-based server). Its ideas (save on the phone
first, sync in the background, survive restarts) are what this design keeps;
its code is not used by the app.
