# Mihon Random Downloads

Personal Mihon source extension that shows a random screen of manga that already exist under Mihon's download directory.

## Chosen behavior

This project intentionally uses Mihon's normal source browsing flow.

That means Random Downloads entries are separate Mihon source identities from the original online-source entries. As a result:

- downloaded badges/state may not match the original entry;
- read progress/history may differ from the original entry;
- source identity is Random Downloads rather than the original source.

This tradeoff is intentional because the desired interaction is:

```text
Browse -> Random Downloads -> random manga screen -> manga -> locally downloaded chapters -> Reader
```

## File-safety boundary

The extension itself does not create, rename, delete, extract, or modify manga/download files.

Runtime implementation:

- opens Mihon's `tachiyomi.db` with `SQLiteDatabase.OPEN_READONLY`;
- reads only directory names/URIs through `DocumentsContract`;
- keeps the download/manga index only in process memory;
- reads folder pages through `ContentResolver.openInputStream()`;
- reads CBZ entries through Mihon's existing `ArchiveReader` via a read-only file descriptor;
- does not create plugin cache/index/record files;
- does not extract CBZ files to disk.

Mihon's own normal source browsing/database/cache behavior is unchanged.

## Performance model

On the first Random Downloads browse in a process:

1. scan `downloads/<source>/<manga>` directory names once;
2. read existing manga metadata from Mihon's DB;
3. intersect both lists in memory;
4. randomly return 20 manga.

Subsequent refreshes only shuffle the in-memory list.

Covers use the original manga `thumbnail_url`, allowing Mihon's existing image cache/network path to handle them instead of opening a CBZ per cover.

When a manga is opened, only that manga's directory is read to find downloaded chapters.

## Reader

Downloaded folder chapters are streamed directly from their existing image files.

Downloaded CBZ chapters are not extracted. The extension enumerates image entry names and then streams the selected entry through Mihon's existing archive reader.

## Build

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

Current candidate:

```text
src/all/randomdownloads/build/outputs/apk/debug/tachiyomi-all.randomdownloads-v1.6.5.apk
```

## Acceptance test

1. Install/trust **Random Downloads 1.6.5**.
2. Open **浏览 -> 图源 -> Random Downloads**.
3. Confirm the first screen eventually shows ~20 random downloaded manga.
4. Re-enter/refresh and confirm the next random batch is much faster.
5. Open a manga and confirm only locally present chapters are shown.
6. Open a folder-based chapter if available.
7. Open a CBZ chapter and flip through multiple pages.
8. Confirm no download file is modified or created by the extension.
