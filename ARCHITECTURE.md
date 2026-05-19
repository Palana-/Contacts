# PhoneBook Architecture

## Current Balance

This app keeps the existing programmatic Android View UI to avoid a risky UI rewrite. The data and image layers are separated so future features, especially server backup or automatic contact sync, can be added without changing screen code first.

## Stack

- UI: Jetpack Compose with Material 3
- Local data: Room
- Async boundary: Kotlin coroutines in repositories, existing background executor for long-running import/export flows
- Image loading: Coil
- Image picking: AndroidX Activity Result Photo Picker
- System contacts: `ContactsContract`

## Data Flow

UI code works with `PhoneContact`.

`ContactRepository` owns persistence:

- reads contacts from Room
- writes local changes to Room
- migrates legacy SharedPreferences JSON once
- exposes `syncNow()` for future server sync

Room stores sync metadata with every contact:

- `remoteId`
- `updatedAt`
- `lastSyncedAt`
- `syncState`

These fields let a future server implementation decide which contacts need upload or merge.

## Server Sync Extension Point

Future server work should implement `ContactSyncDataSource`.

Rules:

- Do not call network APIs directly from activities.
- Put upload/download conflict handling behind repository/data-source classes.
- Keep local Room as the source of truth for UI.
- Mark locally changed contacts as pending before syncing.

## Dependency Rules

- Prefer official AndroidX libraries for core app data and lifecycle concerns.
- Prefer small, maintained community libraries only where they clearly reduce risk or complexity.
- Do not add a new dependency without documenting why the platform or current stack is not enough.

## Current Non-Goals

- Compose is now the app UI layer.
- No multi-module split yet.
- No Hilt yet; the project remains small enough for simple singleton repositories.
- No real server sync yet; only the interface and local metadata are in place.
