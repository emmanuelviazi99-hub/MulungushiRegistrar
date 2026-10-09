# ER diagram

Paste this block into any Mermaid renderer (GitHub renders it natively, or use
https://mermaid.live) for a visual version.

```mermaid
erDiagram
    PROGRAMMES ||--o{ STUDENTS : "has"
    LAB_GROUPS ||--o{ STUDENTS : "contains"
    STUDENTS ||--o| ACCOUNTS : "owns"
    STUDENTS ||--o{ CLAIM_CODES : "has"
    STUDENTS ||--o{ GROUP_REQUESTS : "requests"
    STUDENTS ||--o{ NUMBER_CORRECTION_REQUESTS : "requests"
    LAB_GROUPS ||--o{ GROUP_REQUESTS : "from/to"
    ACCOUNTS ||--o{ GROUP_REQUESTS : "resolves"
    ACCOUNTS ||--o{ NUMBER_CORRECTION_REQUESTS : "resolves"
    ACCOUNTS ||--o{ OPERATION_RECEIPTS : "makes"

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
        char_36 student_id PK "immutable UUID"
        char_9 student_number UK "9 digits, leading zeros kept"
        varchar full_name
        int programme_id FK
        int group_id FK "nullable = Unassigned"
        tinyint active "0 = soft-deleted"
        int version "optimistic concurrency"
        datetime created_at
        datetime updated_at
        datetime deleted_at
        varchar deletion_marker
    }

    ACCOUNTS {
        int account_id PK
        varchar username UK
        varchar password_hash "bcrypt, never plain text"
        enum role "STUDENT or LECTURER"
        char_36 student_id FK "null for lecturers, UK"
        tinyint active
        datetime created_at
    }

    CLAIM_CODES {
        int claim_id PK
        char_36 student_id FK
        varchar code_hash "SHA-256, never plain code"
        datetime expires_at
        datetime used_at "null until consumed"
        tinyint active
        datetime created_at
    }

    GROUP_REQUESTS {
        int request_id PK
        char_36 student_id FK
        int from_group_id FK
        int to_group_id FK
        varchar reason
        enum status "PENDING, APPROVED, DECLINED"
        datetime requested_at
        datetime resolved_at
        int resolved_by FK
    }

    NUMBER_CORRECTION_REQUESTS {
        int request_id PK
        char_36 student_id FK
        char_9 old_number
        char_9 new_number
        varchar reason
        enum status "PENDING, APPROVED, DECLINED"
        datetime requested_at
        datetime resolved_at
        int resolved_by FK
    }

    OPERATION_RECEIPTS {
        char_36 operation_id PK "client-supplied UUID"
        int account_id FK
        char_64 request_hash "detects id reuse with different content"
        smallint status_code
        json response_json
        datetime created_at
    }
```

## Relationship notes

- **One student ↔ at most one account.** `accounts.student_id` is `UNIQUE`,
  so a student can never end up with two logins — this is what makes the
  claim-code flow's "link, don't duplicate" rule enforceable at the schema
  level, not just in application code.
- **A student belongs to at most one group at a time** (`students.group_id`
  is a single nullable foreign key, not a join table) — matches "one
  active student may belong to only one group."
- **Soft delete, not a separate table.** `deleted_at` / `deletion_marker` on
  `students` keep the row (and its reserved `student_number`) instead of
  moving it elsewhere, which would complicate every join above.
