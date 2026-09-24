# Mihon Random Downloads

Personal Mihon source extension that shows a random screen of already-downloaded manga.

## Chosen UX

This project intentionally uses Mihon's normal source browsing flow:

```text
Browse -> Random Downloads -> random manga screen -> manga -> local chapters -> Reader
```

Random Downloads entries are separate Mihon identities from the original online-source entries. Therefore
download badges, reading progress, and source identity may differ from the original entry. This tradeoff is
intentional in favor of direct source browsing.

## Random performance

The extension no longer scans all downloaded manga before choosing 20.

Preferred path:

1. Read Mihon's existing in-memory `DownloadCache.rootDownloadsDir` by reflection.
2. Reservoir-sample 20 manga directly from that already-built cache.
3. Do not touch the filesystem for the random selection itself.

Fallback path, used only when Mihon's download cache is not ready:

1. Read the small list of source directories.
2. Shuffle the sources.
3. Read manga directories from only enough randomly chosen sources to collect 20 entries.
4. Stop immediately after 20 are collected.

The fallback currently samples up to 5 manga per selected source. It favors source diversity over perfectly
uniform per-manga probability, but avoids scanning all ~3000 manga directories.

No plugin index is written to disk. Known paths are cached in process memory only.

## File-safety boundary

The extension itself does not create, rename, delete, extract, or modify manga/download files.

Runtime implementation:

- does **not** open `tachiyomi.db` directly;
- does **not** write any plugin index/cache/record file;
- uses `DocumentsContract` and persisted SAF access read-only;
- reads normal chapter folders through `ContentResolver.openInputStream()`;
- enumerates CBZ entries with `ZipInputStream` read-only;
- streams selected CBZ entries through Mihon's existing `ArchiveReader` using a read-only file descriptor;
- does not extract CBZ files to disk.

Mihon's own normal source browsing/database/image-cache behavior remains unchanged.

## Covers

The manga list uses the first readable local page as a cover through the same read-only image bridge.

Cover loading happens asynchronously after the manga list is returned. For CBZ covers, only the first image
entry is located; the entire archive is not extracted or copied.

## Build

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

Current candidate:

```text
src/all/randomdownloads/build/outputs/apk/debug/tachiyomi-all.randomdownloads-v1.6.6.apk
```

## Acceptance test

1. Install/trust **Random Downloads 1.6.6**.
2. Open **浏览 -> 图源 -> Random Downloads**.
3. Measure first random-page load.
4. Refresh/re-enter and verify the next random page is fast.
5. Open several manga and verify the details page no longer crashes.
6. Verify only locally present chapters are listed.
7. Open a CBZ chapter and flip through multiple pages.
8. Confirm no download file is created, renamed, deleted, or modified by the extension.
