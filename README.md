# Mulungushi Registrar

ICT361 group project: an Android app (Java) for student registration and lab
groups, with a Node.js + MySQL backend.

```
app/                           the Android app (open this folder's parent in Android Studio)
mulungushi-registrar-backend/  the backend the app talks to (see its README.md)
```

## Run it

1. **Backend.** Follow `mulungushi-registrar-backend/README.md`:
   - `npm install`
   - copy `.env.example` to `.env` and fill in MySQL, `JWT_SECRET` and the Gmail SMTP settings
   - `npm run db:setup` (fresh database), or `npm run db:migrate` if you already have one with data
   - `npm run seed:accounts`
   - `npm start`

   Check it with `npm run email:test -- you@gmail.com`.
2. **Tell the app where the backend is**, in
   `app/src/main/java/zm/ac/mulungushi/registrar/ApiConfig.java`:
   - Android emulator on the same laptop: `http://10.0.2.2:3000/api`
   - a phone on a USB cable: `http://127.0.0.1:3000/api`, then run
     `adb reverse tcp:3000 tcp:3000` (again every time you replug)
   - a real phone on the same Wi-Fi: `http://<your laptop's IPv4 address>:3000/api`. Find
     it with `ipconfig`, and allow Node through Windows Firewall when asked.
3. **Run the app** from Android Studio.

## Accounts

- **Students** create their own account in the app: "Create an account" → **Student**, then
  name, student number, NRC, date of birth and an email you can open. A code is emailed (valid
  10 minutes, 5 tries). Type it in, pick your programme and a password.
  - **Not on the roster yet?** Just sign up. Your student record is created when you confirm
    the code (no lab group yet; a lecturer places you). Try e.g. `202399001`, NRC `799001/10/1`.
  - **Already added by a lecturer?** Use the same number, NRC and date of birth they entered.
    Demo roster: `202301001`, born `2003-04-12`, NRC `700001/10/1` (more in `seed.sql`).
- **Lecturers** choose **Lecturer**: email + employee ID must be on the staff list. Add someone
  with `npm run staff:add -- --email … --employee-id … --name "…"`. Demo: `m.chilufya@mulungushi.edu.zm`, `EMP-1002`.
- **Ready-made lecturer login** from `npm run seed:accounts`: `b.nyirenda@mulungushi.edu.zm`
  with the `SEED_LECTURER_PASSWORD` from the backend `.env`.

Sign-in, sign-up and forgotten password all go to the backend (`ApiAuthService`). If the
backend cannot be reached the app says so; nobody is signed in.

## Storage: on the phone and on the server

The roster, groups and requests live on the server (MySQL). The app keeps a copy
in a Room database on the phone and saves every change there first, so it works
offline: changes are labelled "Waiting to upload" and WorkManager uploads them
when the phone is back online, each exactly once. If someone else changed the
same student first, the app shows both versions and asks which to keep. How it
works and how to show it on a phone: [`STORAGE_AND_SYNC.md`](STORAGE_AND_SYNC.md).

Screens reload every 30 seconds while open and on a pull-down; students who turn
alerts on get a notification when a lecturer answers, even with the app closed.

## Accessibility and UI details

The UI/UX team's work (Joshua) is merged on top of the server and storage code:
change password in Edit my details, the roster keeping search and filters on
rotation, "Show more" pagination, loading and "session expired" states, a
"limited cached results" label, a Share group summary button (counts only, no
names), a registration draft kept on the phone when offline, the keyboard no
longer covering sign-up fields, Sync page "Last synced" and Sync now, and no
"Unassigned" choice in a student's group-change picker.


Statuses are said in words, not by colour alone. TalkBack reads each roster row
as one sentence and announces search results; decorative icons are skipped.
Buttons, tabs and bars grow with the phone's font size (they have minimum
heights, not fixed ones), so text isn't cut off at large sizes.

## Never commit

`local.properties`, `.idea/`, `build/`, the backend's `node_modules/` and `.env` (all in `.gitignore`).
