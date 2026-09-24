# Mihon Random Downloads

Personal Mihon source extension that shows a random screen of already-downloaded manga.

## UX

```text
娴忚 -> 鍥炬簮 -> Random Downloads
     -> 闅忔満 20 鏈?     -> 婕敾
     -> 鏈湴绔犺妭
     -> Reader
```

Random Downloads entries intentionally use their own Mihon source identity. Download badges, reading progress,
and original-source identity may therefore differ from the original entries. This is the chosen tradeoff for
direct source browsing.

## No reflection

The extension does not use reflection.

Runtime source code contains no:

- `Class.forName`
- `javaClass`
- `getMethod` / `getDeclaredField`
- `Injekt.getInstance`
- reflective `invoke`

The extension does not access Mihon internal `DownloadCache` or `ArchiveReader`.

## Random performance

The extension does not scan all ~3000 manga directories before choosing 20.

It:

1. reads and caches the small list of download-source directories in process memory;
2. shuffles those source directories;
3. reads manga directories from only enough selected sources to fill the screen;
4. samples at most 5 manga per selected source;
5. stops immediately when 20 manga have been collected.

This favors source diversity instead of mathematically perfect per-manga uniformity, but avoids a full download-tree scan.

No random index is written to disk.

## File-safety boundary

The extension itself does not create, rename, delete, extract, or modify manga/download files.

Runtime implementation:

- does not open `tachiyomi.db`;
- does not write plugin index/cache/record files;
- uses `DocumentsContract` / `ContentResolver` read-only;
- opens files with read-only `ParcelFileDescriptor`;
- reads folder chapters directly from existing image files;
- reads CBZ central directories and entries with the extension framework's public `keiyoushi.zip` API;
- performs range reads through a read-only file descriptor;
- never extracts CBZ files to disk.

Mihon's own normal source/database/image-cache behavior is unchanged.

## Covers

The extension first uses an existing manga-level cover file:

```text
cover.jpg
cover.jpeg
cover.png
cover.webp
cover.avif
```

On this device, 2910 of 2924 downloaded manga directories already have such a cover.

Only when no cover file exists does the extension fall back to the first page of a downloaded chapter.

## Refresh / reroll

Mihon 0.20.4 does not show a generic refresh button on a source page that already has results.

Random Downloads therefore exposes both:

- **鐑棬**
- **鏈€杩戞洿鏂?*

Both return a fresh random batch. Switching between the two chips rebuilds the pager and rerolls the 20 manga.

## Build

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

Current candidate:

```text
src/all/randomdownloads/build/outputs/apk/debug/tachiyomi-all.randomdownloads-v1.6.11.apk
```

## Acceptance test

1. Install/trust **Random Downloads 1.6.11**.
2. Open **娴忚 -> 鍥炬簮 -> Random Downloads**.
3. Confirm 20 manga appear without a full-tree scan.
4. Switch **鐑棬 / 鏈€杩戞洿鏂?* and confirm a new batch appears.
5. Open several manga and verify chapters open normally.
6. Open a known CBZ chapter and confirm its full page count is returned.
7. Flip through multiple CBZ pages.
8. Confirm no download file is created, renamed, deleted, extracted, or modified by the extension.

