# ER diagram

Paste the block into any Mermaid renderer (GitHub shows it automatically, or
use https://mermaid.live). Columns marked *derived* are MySQL generated
columns: the database computes them from other columns, nobody writes them.

```mermaid
erDiagram
    PROGRAMMES ||--o{ STUDENTS : "studies"
    LAB_GROUPS ||--o{ STUDENTS : "seats (max 15 active)"
    STUDENTS ||--o| ACCOUNTS : "signs in as"
    STAFF_ROSTER ||--o| ACCOUNTS : "signs in as"
    STUDENTS ||--o{ VERIFICATION_CODES : "student sign-up"
    STAFF_ROSTER ||--o{ VERIFICATION_CODES : "lecturer sign-up"
    ACCOUNTS ||--o{ VERIFICATION_CODES : "password reset"
    STUDENTS ||--o{ CHANGE_REQUESTS : "asks"
    LAB_GROUPS ||--o{ CHANGE_REQUESTS : "from / to"
    ACCOUNTS ||--o{ CHANGE_REQUESTS : "resolves"
    ACCOUNTS ||--o{ OPERATION_RECEIPTS : "retries"

    PROGRAMMES {
        int programme_id PK
        varchar code UK "CS, IT, DS"
        varchar name
    }

    LAB_GROUPS {
        int group_id PK
        varchar group_code UK "G01..G04"
        tinyint capacity "15"
        tinyint active
    }

    STUDENTS {
        char36 student_id PK "random UUID, never changes"
        char9 student_number UK "correctable via request"
        char11 nrc UK "123456/10/1, correctable by a lecturer"
        date date_of_birth
        varchar full_name
        int programme_id FK
        int group_id FK "NULL = Unassigned"
        int version "optimistic concurrency"
        datetime deleted_at "set = soft-deleted"
        tinyint active "derived: deleted_at IS NULL"
    }

    STAFF_ROSTER {
        int staff_id PK
        varchar employee_id UK "EMP-1001"
        varchar email UK "who MAY sign up"
        varchar full_name
        tinyint active
    }

    ACCOUNTS {
        int account_id PK
        varchar email UK "login, unique across everyone"
        varchar phone "+260..."
        varchar password_hash "bcrypt"
        char36 student_id FK,UK "students ..."
        int staff_id FK,UK "... or lecturers, exactly one"
        enum role "derived: from which link is set"
        tinyint active
        tinyint failed_logins
        datetime locked_until
        datetime password_changed_at "older tokens rejected"
    }

    VERIFICATION_CODES {
        int code_id PK
        enum purpose "STUDENT_SIGNUP, STAFF_SIGNUP, PASSWORD_RESET"
        varchar email "where it was sent"
        char64 code_hash "HMAC-SHA256, never the code"
        char36 student_id FK "only for STUDENT_SIGNUP"
        int staff_id FK "only for STAFF_SIGNUP"
        int account_id FK "only for PASSWORD_RESET"
        varchar phone "sign-up only"
        tinyint attempts "max 5"
        datetime expires_at "10 minutes"
        datetime verified_at
        datetime consumed_at "single use"
    }

    CHANGE_REQUESTS {
        int request_id PK
        enum kind "GROUP or NUMBER"
        char36 student_id FK
        int from_group_id FK "GROUP: history"
        int to_group_id FK "GROUP: target, NULL = Unassigned"
        char9 old_number "NUMBER: history"
        char9 new_number "NUMBER: target"
        varchar reason
        enum status "PENDING, APPROVED, DECLINED"
        int resolved_by FK
        datetime resolved_at
        tinyint is_pending "derived; UNIQUE with student_id + kind"
    }

    OPERATION_RECEIPTS {
        char36 operation_id PK "from the app"
        int account_id FK
        char64 request_hash
        smallint status_code
        json response_json
    }
```

## Rules the database itself enforces

| Rule | How |
|---|---|
| A student number, and an NRC, belongs to one person forever (even after deletion) | `UNIQUE` on `students.student_number` and `students.nrc`; soft delete keeps the row |
| NRC and student number have the right shape | `CHECK` with a regular expression |
| One login per student, one per lecturer, one per email | `UNIQUE` on `accounts.student_id`, `staff_id`, `email` |
| An account is a student's or a lecturer's, never both or neither | `CHECK ((student_id IS NULL) <> (staff_id IS NULL))`; `role` is derived from it |
| A verification code is linked to exactly the person its purpose needs | `CHECK chk_vcode_link` |
| One open request per student per kind | `UNIQUE (student_id, kind, is_pending)`; `is_pending` is NULL once resolved |
| A request's shape matches its kind; resolved requests have a resolved time | `CHECK chk_creq_shape`, `chk_creq_resolved` |
| Nothing can point at a row that does not exist | Foreign keys everywhere |
| At most 15 active students per group | Not a constraint (MySQL cannot count in a CHECK): one locked transaction in `services/group.service.js` that every path uses |

## Why `student_id`, not the NRC, is the primary key

The NRC is unique for every citizen, so it is `UNIQUE NOT NULL`. It is not the
primary key because a primary key must never change (an NRC can be typed wrong
and need correcting) and is copied into every foreign key, URL and log line,
where a national ID number should not be. `student_id` is a random UUID that
never changes; the NRC is a unique, correctable field next to it.
