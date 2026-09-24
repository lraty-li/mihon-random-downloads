# Mihon Random Downloads

Personal Mihon extension for opening a random selection of manga that already have local downloads.

## Current design

The old virtual-source PoC was removed because Mihon's normal source browsing pipeline persists returned
network manga as a separate source identity. That caused incorrect downloaded/read state.

Version 1.6.3 uses the extension's **Settings** screen instead:

1. Open Mihon's existing `tachiyomi.db` with SQLite `OPEN_READONLY`.
2. Read existing manga IDs, source IDs, and titles.
3. Read only the two directory levels under Mihon's existing `downloads/<source>/<manga>` tree.
4. Use Mihon's existing `DownloadProvider` naming functions to match download folders back to original manga rows.
5. Keep the matched list only in process memory.
6. Randomly show 20 original manga entries in the extension Settings screen.
7. Clicking an item launches Mihon's existing `SHOW_MANGA` action with the original manga ID.

The normal source Browse/Search APIs intentionally return an empty list so they cannot create virtual or
duplicate manga entries.

## Write-safety rules

Runtime plugin code must not:

- create an index/cache/record file;
- create, rename, delete, extract, or modify manga/download files;
- write to Mihon's manga/chapter/download tables;
- generate fake local chapters/pages;
- use the previous CBZ extraction/image-interceptor path.

The directory scan uses `DocumentsContract` queries only. The database connection is opened with
`SQLiteDatabase.OPEN_READONLY`.

Mihon's own normal UI behavior after opening an original manga (history/recently viewed/read state, etc.) is
outside this restriction and behaves exactly as it normally would.

## Performance

The first scan reads the source and manga directory names once and intersects them with existing Mihon manga
rows. The resulting eligible manga list is held only in memory.

**换一批** only shuffles the in-memory list. It does not rescan disk.

**重新扫描下载目录** explicitly performs the read-only directory scan again.

## Build

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

Current candidate:

```text
src/all/randomdownloads/build/outputs/apk/debug/tachiyomi-all.randomdownloads-v1.6.3.apk
```

## Acceptance test

1. Install/trust **Random Downloads 1.6.3**.
2. In Mihon: **浏览 -> 插件 -> Random Downloads -> 设置**.
3. Wait for the initial read-only scan.
4. Confirm the status reports a plausible number of downloaded manga.
5. Tap **换一批** and confirm it changes immediately without another long scan.
6. Tap a manga entry and confirm Mihon opens its original manga page.
7. Confirm downloaded chapter indicators, read progress, source identity, and library state are the original ones.
8. Do not use the normal Random Downloads source Browse page; it is intentionally empty.
