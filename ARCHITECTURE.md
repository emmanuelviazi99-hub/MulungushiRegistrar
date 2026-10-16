# App architecture: MVVM in Java + XML

The app follows Model–View–ViewModel with AndroidX `ViewModel` and `LiveData`,
in Java with XML layouts, with one-way data flow:

```
XML layout + Activity (View)  ──actions──▶  ViewModel  ──calls──▶  Repository  ──▶  backend API  ──▶  MySQL
        ▲                                      │                     │
        └──────── observes LiveData ◀──────────┘ ◀── listener ───────┘ (Room copy + outbox on the phone)
```

- **View** (`*Activity`, `res/layout/*.xml`, small View helpers such as
  `RosterFilterSheet`): draws what the ViewModel publishes and passes on taps and
  typing. No HTTP, no database, no business rules.
- **ViewModel**: holds screen state, survives rotation, allows one action at a
  time, and publishes `LiveData`. It holds no Activity, View or Context. One-time
  messages use `Event` so a recreated screen does not show them again.
- **Repository / data layer**:
  - `StudentRepository` is the in-memory store screens read, with the search and
    filtering rules applied through `RosterQuery`.
  - `RosterApi` sends requests through the server, the Room copy and the outbox
    (see `STORAGE_AND_SYNC.md`).
  - `ApiAuthService` (`AuthService`) handles sign-in and sign-up.
  - `Session` holds the signed-in account.

  Repositories call `StudentRepository` listeners when data changes, and the
  ViewModels republish.
- **Rules in plain Java**, unit-tested without Android: `RosterQuery`
  (search and filters), `GroupRequestRules` ("Request for Group" or "Request
  for Group Change", and what may be sent), `Greeting` ("Welcome" or "Welcome
  back"), plus the existing validators and `SyncEngine`.

| Feature | View | ViewModel | Data |
|---|---|---|---|
| Sign-in form | `LoginActivity` | `LoginViewModel` | `ApiAuthService` → `POST /auth/login` |
| Welcome line, role checks | `StudentHomeActivity`, `LecturerDashboardActivity` | `AuthViewModel` (+ `Greeting`) | `Session` ← `firstSignIn` from the server |
| Roster search and filters | `LecturerRosterActivity`, `RosterFilterSheet` | `LecturerViewModel` (+ `RosterQuery`) | `StudentRepository.search()` ← `RosterApi.loadRoster()` → `GET /students` |
| Group request | `GroupChangeActivity`, home card | `GroupViewModel` (+ `GroupRequestRules`) | `RosterApi.loadMine()`, `requestGroupChange()`, `cancelGroupChange()` → `/students/me`, `/group-requests`, `/my-requests/group` |

**Why one Java package?** The data classes (`RosterApi`, `StudentRepository`,
`Session`, …) are package-private on purpose, so only the app's own code can
use them. Splitting the package into `ui/`, `data/` and so on would mean making
them public. The layers are kept apart by what each class may call (above), not
by folders.

**Not yet moved to ViewModels:** the sign-up, forgot-password, edit-details,
requests, sync and my-group screens. They still call `RosterApi` or `Auth`
directly from the Activity. They work as before and can be moved the same way:
state into a ViewModel, rules into a plain-Java class.

**Security.** The server enforces roles on every endpoint: lecturer-only
endpoints return 403 to students. The app also sends anyone who is not a
lecturer away from lecturer screens (`RosterUi.lecturerOrLeave`). The app holds
no database credentials: it only talks to the backend API.
