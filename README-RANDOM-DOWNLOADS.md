# Mihon Random Downloads

Personal Mihon source extension that treats Mihon's existing download directory as a virtual source.

## PoC goal

- Browse -> show 20 random manga from all entries under Mihon's `downloads/` directory.
- Search -> search downloaded manga/source directory names.
- Manga details -> list locally downloaded chapters.
- Reader -> open downloaded chapter folders or CBZ files without contacting the original source.
- Covers -> use the first readable image from a downloaded chapter.

## How it works

The extension runs inside Mihon and reads Mihon's configured Storage Access Framework tree URI from the host app's default preferences. It then traverses:

```text
<storage>/
  downloads/
    <source>/
      <manga>/
        <chapter>.cbz
        <chapter>/
          001.jpg
          ...
```

Mihon expects HTTP image URLs from a normal source. The extension therefore exposes fake URLs under
`https://random-downloads.invalid/` and intercepts them with its own OkHttp interceptor.

For chapter reading, pages are staged into Mihon's cache directory. CBZ archives are extracted only when a
chapter is opened; the staged chapter cache is capped at roughly 512 MiB.

## Build

From this repository root:

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

The extension module is:

```text
src/all/randomdownloads
```

## Current scope

This is intentionally a personal PoC. Entries in Random Downloads are separate Mihon manga identities from
their original online-source entries, so reading progress is not shared with the original source entry.

## Real-device validation checklist

The code and APK compile successfully on Windows. The remaining validation requires a phone/tablet
running Mihon with real downloaded manga.

1. Install the debug APK from:
   `src/all/randomdownloads/build/outputs/apk/debug/tachiyomi-all.randomdownloads-v1.6.1.apk`
2. In Mihon, enable/trust the **Random Downloads** extension if prompted.
3. Open **Browse -> Sources -> Random Downloads**.
4. Confirm that roughly 20 manga from different original sources appear.
5. Pull to refresh/re-enter the source and confirm the selection changes.
6. Open a manga and confirm only locally downloaded chapters are listed.
7. Open a downloaded CBZ chapter and flip through at least 20 pages.
8. Go back, open a different chapter, then return to the first chapter to exercise the local cache.
9. Search for part of a manga title and for an original source directory name.
10. If anything fails, capture the exact screen/error plus `adb logcat` around the failure.

The first device test should especially verify that the installed Mihon build uses the expected
`__APP_STATE_storage_dir` preference key and that its persisted SAF permission is visible from the
extension process. Those are host/runtime facts that cannot be fully proven by an APK-only build.
