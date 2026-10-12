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
   - load `schema.sql` and `seed.sql`
   - `npm run seed:accounts`
   - `npm start`

   Check it with `npm run email:test -- you@gmail.com`.
2. **Tell the app where the backend is**, in
   `app/src/main/java/zm/ac/mulungushi/registrar/ApiConfig.java`:
   - Android emulator on the same laptop: `http://10.0.2.2:3000/api`
   - a real phone on the same Wi-Fi: `http://<your laptop's IPv4 address>:3000/api`. Find
     it with `ipconfig`, and allow Node through Windows Firewall when asked.
3. **Run the app** from Android Studio.

## Accounts

- **Students** create their own account in the app. On "Create an account", choose **Student**,
  enter the student number, date of birth and NRC exactly as the lecturer added them, plus an
  email you can open. A code is emailed (valid 10 minutes, 5 tries). Type it in, then choose a
  password.
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
