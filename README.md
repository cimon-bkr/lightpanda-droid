# Lightpanda for Android (unofficial)

An Android app that runs [Lightpanda](https://github.com/lightpanda-io/browser), a headless browser
for scripts and AI agents. It has no screen: you start a server in the app and connect to
`ws://127.0.0.1:9222` from Puppeteer, Playwright or another app, or load a single page from the app
to see what Lightpanda makes of it.

> **Status: preview.** Built in CI; not yet confirmed on a real phone. Please report what the
> in-app log says if it does not start.

## How it works

Lightpanda has no Android build, and its official Linux build needs glibc, which Android lacks. The
app bundles that official build plus the glibc loader and libraries from Termux's glibc packages and
starts Lightpanda through the loader. Two things glibc cannot do on Android are handled by the app:

- **Host name lookups:** the app runs a small password-protected SOCKS5 proxy on `127.0.0.1` and
  points Lightpanda at it, so names are resolved by Android. TLS stays end to end.
- **Certificates:** the app writes Android's trusted certificates to a file and gives it to
  Lightpanda.

Telemetry is switched off (`LIGHTPANDA_DISABLE_TELEMETRY`).

## Install

Download `lightpanda-android-arm64.apk` from the Releases page (64-bit ARM phones, Android 10+).
It is signed with a debug key, so uninstall before installing a later build.

## Safety

While the server runs, **any app on the phone can connect** to the DevTools port; there is no
password. Stop the server when you do not need it.

## Build

```sh
bash scripts/fetch-runtime.sh        # downloads Lightpanda and glibc (needs network)
gradle assembleRelease               # JDK 17, Android SDK 35, Gradle 8.11+
```

## Licenses

App code: MIT. Bundled Lightpanda: AGPL-3.0. Bundled glibc: LGPL-2.1. See `NOTICE.md`.
