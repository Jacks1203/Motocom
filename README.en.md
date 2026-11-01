# Motocom

**English** · [Español](README.md)

Android app that sends the image of another app on your phone to the EasyConn TFT screen of your
motorcycle, without Carbit Ride and without internet.

**It works with any navigation app, not just Waze and Google Maps:** you pick it from the list of
apps installed on your phone.

The phone joins the Wi‑Fi Direct network of the bike's screen, creates a 592 × 816 virtual display
and sends the chosen app to the bike as H.264 video. You can bring that app back to the phone to
type a destination and then send it to the bike again.

The app is available in Spanish and English: choose the language at the bottom of the main screen
(Spanish by default).

> **Unofficial project.** It has no connection with any motorcycle manufacturer, EasyConn or Carbit. It has only been tested
> with one bike and a few phones. Use it at your own risk and always test with the bike stopped.

## Video

This [YouTube video](https://youtu.be/eCLog0vlEC8) (in Spanish) shows how it works, which
permissions it uses and how to set it up step by step.

[![Motocom video on YouTube](https://img.youtube.com/vi/eCLog0vlEC8/hqdefault.jpg)](https://youtu.be/eCLog0vlEC8)

## Install

1. On your phone, download the latest `motocom-vX.Y.apk` from
   [Releases](https://github.com/Jacks1203/Motocom/releases).
2. Open it and allow installing apps from unknown sources if Android asks.
3. Follow the [connection guide](GUIDE.md): the first time, you have to turn on wireless debugging
   and pair Motocom with itself (no computer or Shizuku needed).

Since version 0.7 the APK is signed with Motocom's own key. If you had 0.6 or earlier, uninstall it
before installing 0.7; later updates install over the previous version.

Requirements: Android 10 or later (Android 11 to pair without a computer; Android 13 to keep the
image going while the phone is locked).

## Why it needs ADB

To show your navigation app on the bike, Motocom creates a second display the size of the bike's
screen, opens the chosen app on it and moves the app between that display and the phone's. Android
doesn't let a normal app open other apps on a display it has created: only the "shell" user can do
that, the same user a computer connected through `adb` uses (and apps like scrcpy).

That's why Motocom uses Android's **wireless debugging** to connect through ADB **to the phone
itself** (`127.0.0.1`), with no root, no computer and no Shizuku:

- Pairing with the code authorizes only Motocom's key on that phone. You do it once.
- Through ADB, Motocom starts a small process of its own with shell permissions. That process only
  does four things: create the bike's display, move the chosen app to it or back to the phone,
  remove that display and shut down. It only answers to Motocom.
- The first time, Motocom uses ADB to grant itself the permission to change system settings
  (`WRITE_SECURE_SETTINGS`), only so it can turn wireless debugging on and off by itself afterwards.
- Nothing goes out to the internet: ADB stays inside the phone.

That process keeps running until you restart the phone or update or uninstall Motocom. To withdraw
the authorization, go to Developer options → "Revoke debugging authorizations" and restart the
phone. The `WRITE_SECURE_SETTINGS` permission stays granted until you uninstall Motocom.

On some Android 14 or later versions (such as Android 17 on Pixel), the bike's display would turn
off along with the phone's. In that case the process creates it as a "connected device", like the
displays sent to a car, and to do so it pairs a fictitious "Moto" device with the system. That
pairing is removed when you disconnect.

## Permissions

| Permission | What Motocom uses it for |
|---|---|
| Camera | Reading the QR code on the bike's screen (its Wi‑Fi name and password). Only while you scan the QR. |
| Nearby Wi‑Fi devices (Android 13+) | Finding the Wi‑Fi Direct network of the bike's screen. Declared as "not used for location". Essential. |
| Location (Android 12 or earlier only) | Those Android versions require it to search for Wi‑Fi Direct devices. Motocom doesn't read or store your location. It isn't requested on Android 13+. |
| View and change Wi‑Fi and network | Connecting to the bike's Wi‑Fi Direct network. |
| Network access (`INTERNET`) | Talking to the bike over its Wi‑Fi and to ADB inside the phone. Motocom doesn't use the internet (the map app may). |
| Notifications | Showing the bike screen notification while the image is being sent. Optional: it works the same without it. |
| Foreground service (connected device) | Keeping the image going with the app in the background or the phone locked. |
| Keep the phone awake (`WAKE_LOCK`) | Stopping the phone from sleeping while it sends the image with the screen off. |
| Request removal of battery optimization | Offering the "Allow" button so Android doesn't cut off the image while the phone is locked. Optional. |
| Change system settings (`WRITE_SECURE_SETTINGS`) | Turning wireless debugging on and off by itself. It can't be requested from the app: Motocom grants it to itself through ADB (see above). |
| See installed apps | Showing the list to choose which app goes to the bike. |

Motocom's ports only accept connections from the bike's screen, not from other devices on the Wi‑Fi
network the phone is on.

Motocom has no ads or analytics and doesn't send data to any server. Each connection's log is stored
only on the phone, and it only leaves the phone if you share it from the Log screen.

## Build

With the Android SDK installed and `local.properties` pointing to it (`sdk.dir=…`):

```
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. The published version is built with
`./gradlew assembleRelease` (without `-PcableTests=true`, which is only for testing with
`codigo/moto_falsa.py` over the cable) and needs the signing key, which isn't in the repository
(`keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`).

## Documentation

- [GUIDE.md](GUIDE.md): installation and step-by-step use.
- [LEEME.en.md](LEEME.en.md): how the protocol of the bike's screen works.
- `codigo/`: original reference code for the connection with the bike and `moto_falsa.py`, a
  simulator for testing without a bike.

## License

[MIT](LICENSE)
