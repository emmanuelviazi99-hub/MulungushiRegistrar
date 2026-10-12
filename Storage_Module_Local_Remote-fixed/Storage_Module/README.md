# Storage Module v3 - Local + Remote (Mulungushi Registrar)

Offline-first: the app always reads and writes the phone's local storage. Changes upload to the
server in the background and the server's list is pulled back, whenever the phone is online.

**These files are already integrated into the corrected frontend project** (same package,
`app/src/main/java/zm/ac/mulungushi/registrar/`), and the backend already contains the remote
storage (MySQL table `student_sync_records`, `POST/GET /api/students/sync`). This folder is the
module's own copy, kept in step with the app.

## Student record
`id, firstName, lastName, number, programme, group, active, email, registeredAt, pending*, notice*, updatedAt`
- First name and last name are two fields everywhere (the old single `name` is split once when
  data saved by the earlier build is loaded).
- `number` is always a String of exactly nine digits, so leading zeros survive.
- `registeredAt` (epoch ms, 0 = not self-registered) is the registration/verification state.
- No phone, NRC or password fields.

## How sync works
- Local save: every change goes through `StudentRepository`, which writes to SharedPreferences
  with `commit()` (on disk before the call returns). A registration is saved this way
  immediately - it never waits for the server.
- Each student has `updatedAt` (epoch ms). Per student id the newest copy wins.
- A student is "waiting to upload" while its content differs from what the server last held.
  `pendingSyncCount()` / `isPendingSync(id)` report it.
- Sync runs at app start, when the network returns, about 1.5 s after any local save, straight
  away after a registration, and - after a failed upload - again after 15 s, 30 s, 1 min, 2 min,
  then every 5 min while anything is waiting.
- One call: `POST {BASE_URL}/students/sync` with `{"students":[changed ones]}` and header
  `X-Sync-Key`; reply `{"success":true,"students":[all],"rejected":[{id,error,message}]}`.
- A failed or refused upload never deletes local data; the record stays saved and waiting.
- No duplicates: the server keeps one row per id and one row per student number, so a retried
  upload (or two phones) can never create a second copy. Locally, a server record whose number
  is held by another local record replaces it only if that local record has no unsynced changes.
- Demo sign-in records (id starts with `demo-`) are never uploaded.
- NOT synced: the request history (Answered list) and the demo conflict flag. They stay on each phone.

## Phone side - android/ (package zm.ac.mulungushi.registrar)
LocalStorage, StudentRepository, RegistrarApplication, Student, SyncMerge, SyncManager,
ApiRemoteStorage, RemoteConfig. Tests: `android/test/.../SyncMergeTest.java` (JVM),
`android/androidTest/.../LocalStorageTest.java` (device).
1. Manifest: `android:name=".RegistrarApplication"` on `<application>` (done in the frontend).
   INTERNET and ACCESS_NETWORK_STATE are already there.
2. The server address comes from `ApiConfig.BASE_URL`. `RemoteConfig.SYNC_KEY` must equal
   `SYNC_KEY` in the backend's `.env` (both `change-me` in development).
3. Screens must change data only through `StudentRepository` methods, or it will not be saved or synced.

## Server side - server/ (stand-alone test server)
Not needed with the Mulungushi Registrar backend, which has the same contract built in.
    cd server
    node test.js                              # 16 checks, all should PASS
    SYNC_KEY=change-me node server.js         # listens on port 3000 (set PORT to change)
Same validation as the backend: first/last name, number = 9-digit string, known programme,
valid email; invalid records come back in `rejected`. Data is stored in `students-data.json`.

## Known limits
- Last-write-wins: if two phones edit the same student offline, the later edit replaces the earlier one.
- Plain HTTP and one shared key in development. Use HTTPS and per-user auth before real data.
- "Newest" uses each phone's clock; a wrong phone clock can win unfairly.
- The sync table (`student_sync_records`) is the phones' shared list. Edits made on a phone are
  not copied into the backend's `students` roster table (registration writes both: the roster
  row at verification, the sync row on the phone's first upload).
