# Movia database schema source

The physical Room schema source of truth is:

`../../android/app/schemas/app.movia.android.data.database.MoviaDatabase/`

Gradle is configured to read `$projectDir/schemas`, so this Android path is intentionally retained as the build-required location. The former `database/schema/app.movia.android.data.database.MoviaDatabase/` directory was an exact byte-for-byte duplicate and has been removed.

Files in the canonical schema directory:

- `1.json` — 4,988 bytes — SHA-256 `61951e3fa41cc154c5112dd1aa18b8f352ebb7e43642de31dc71149dd2361d79`
- `2.json` — 5,743 bytes — SHA-256 `7867f1943c5d5f752202290ff387806e3edd7f5fd17619479008d949c6dc9416`
