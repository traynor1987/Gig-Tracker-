# Gig Tracker Android debug wrapper

This repository contains the native Android shell for the existing [Gig Tracker](https://gig-tracker.traynor1987.chatgpt.site). It does not replace or fork the tracker UI: the hosted PWA remains the updateable app and source of truth.

This project loads the existing hosted Gig Tracker in an origin-locked WebView. The PWA remains the UI and source of truth; Android adds one foreground location service and forwards canonical `android_native` samples into the same GPS ingestion pipeline used by browser geolocation.

## Lifecycle

- Start Session starts the foreground location service.
- Start Break removes Android location updates while keeping the paused foreground service alive.
- End Break resumes Android location updates with the PWA establishing a fresh mileage anchor.
- End Session removes location updates and stops the service.
- An active delivery uses the same samples as the session and stores a separate subset segment; there is no second watcher or service.

Native points are journalled until the trusted page acknowledges them, so WebView suspension does not discard screen-off/background fixes. The queue is scoped by session ID to prevent stale points entering a later session.

## Location permissions

The wrapper requests precise foreground location first. On Android 10 it then requests background location; on Android 11 and newer it explains why it is needed and opens the app's Android settings so the user can choose **Allow all the time**. The same control remains available under **Settings → GPS Session Mileage**.

Background permission never enables unrestricted tracking. The native service runs only for an active Gig Tracker session, pauses location updates during a break, resumes with a fresh anchor, and stops at End Session. Android always shows an ongoing foreground-service notification while work GPS is active.

## Build

Install Android API 35 and Build Tools 35.0.0, then run:

```sh
./build-debug.sh /absolute/path/to/android-sdk
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Every push to `main` also runs **Build debug APK** in GitHub Actions. Its `gig-tracker-debug-apk` artifact contains the installable debug APK. The workflow caches its debug-only signing key so later CI builds remain install-compatible while that cache exists; it is not release signing.

## Local data

Android WebView storage is isolated from Chrome/PWA storage by Android. Installing this wrapper does not modify or erase the existing PWA data, but the first APK install cannot read Chrome's IndexedDB directly. Export a JSON backup from the PWA and import it inside the APK to copy existing records. Later APK upgrades with the same application ID retain the wrapper's own local data.
