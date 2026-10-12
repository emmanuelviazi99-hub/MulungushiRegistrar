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

## What still runs on the phone only

The roster, group changes, number corrections and sync screens still use the in-memory
`StudentRepository` (demo data). After sign-in, the signed-in student's real record from the
server is copied into that list. Moving those screens to the backend's `/api/students`,
`/api/groups` and request endpoints is the next step.

## Never commit

`local.properties`, `.idea/`, `build/`, the backend's `node_modules/` and `.env` (all in `.gitignore`).
