# Motocom: bike screen protocol

**English** · [Español](LEEME.md)

The current app shows other apps on the TFT and uses **direct ADB, without Shizuku**. To install and use it, see the [connection guide](GUIDE.md). What follows documents the bike's protocol and where each part is in Motocom's code.

# How the phone connects to the bike's screen

Guide for taking the direct connection with the bike's TFT screen, without Carbit Ride, to **another
project**. It's the one Motocom uses, and it has **worked with the real bike** since 2026-09-30.

**In short:** the phone joins the bike's own Wi‑Fi (no Bluetooth or internet) and tells it that it's
there; the bike connects to the phone, asks for a video the size of its screen, and the phone keeps
sending it. What you see on the bike is whatever the app draws in that video.

---

## 1. The bike

- EasyConn screen (the same one Carbit Ride uses, `net.easyconn.easyride.wws`).
- Wi‑Fi Direct: **`HJ-5G-XXXXXX`** (as a normal network, `DIRECT-HJ-5G-XXXXXX`, with the password given by the QR).
- HUID `XXXXXXXXXXXX`, `sdkVersion 1.0.14`, `pxcVersion 1.0.2`, `flavor 51`, `channel "37512"`.
- Asks for a **592 × 816** (portrait) image, H.264, 4 Mbit/s, and requests frames one at a time.
- **Not a touchscreen**, and its buttons send nothing useful to the phone (they only change what the bike shows).
- **Asks for no licence or encryption.** No internet or EasyConn secret is needed.
- It already has its own strip with speed, revs, temperature and fuel: no need to repeat them.

### The QR on the bike's screen

The bike shows a QR that isn't a key; it only says what its Wi‑Fi is called:

```
http://www.carbit.com.cn/down6/…?modelid=37512&sn=XXXX&action=9
    &ssid=DIRECT-HJ-5G-XXXXXX&pwd=xxxxxxxx&auth=WPA-PSK&name=HJ-5G-XXXXXX
```

`BikeQr.parse()` reads it. With a QR, the search is for the device with that exact name; without
one, for any device starting with `HJ-`. An app for a single bike could hard-code the data and skip
the QR.

---

## 2. The steps, in order

```
 phone                                             bike (192.168.13.1)
 ─────                                             ────
 1. opens its ports 10920, 10921, 10922
 2. joins the bike's Wi-Fi Direct        ───────►  (the bike is the group owner)
    the phone ends up on 192.168.13.x
 3. "knocks": TCP to 192.168.13.1:10930 ───────►
    PXC 0x70000010 {"phoneType":"Android",
                    "packageName":"…"}
                                        ◄───────   0x70000011 {"status":"true"}
 4.                                     ◄───────   the bike opens 4 connections TO THE PHONE:
                                                   2 to 10922 (conversation)
                                                   1 to 10921 (image control)
                                                   1 to 10920 (image)
 5. answers everything and sends H.264 each time the bike asks for a frame
```

The important part: **the phone is the server**. Without the "knock" in step 3, the bike does
nothing. The first knock may arrive too early: it's repeated every 3 s until the bike
introduces itself (`Moto.knockUntilHeard`).

### Step 2: Wi‑Fi Direct (`BikeWifi.kt`)

- `WifiP2pManager.discoverPeers()`, and on `WIFI_P2P_PEERS_CHANGED_ACTION` look for the device by name.
- Connect like Carbit: `WifiP2pConfig` with `wps.setup = WpsInfo.PBC` and **`groupOwnerIntent = 0`**
  (the bike has to be the group owner; if the phone becomes owner, the bike's address isn't known).
  Carbit took about 7 s; ours, 2 s.
- If it's still out after 25 s, plan B (Android 10+): `WifiP2pConfig.Builder().setNetworkName(ssid)
  .setPassphrase(password from the QR)`.
- On `WIFI_P2P_CONNECTION_CHANGED_ACTION` → `requestConnectionInfo`: if `groupFormed` and we aren't
  the owner, `groupOwnerAddress` is the bike. The phone's own address is the IPv4 address of its
  interfaces that shares the first three numbers with the bike; **the knock has to be bound to that
  address** (`socket.bind`) because the phone may also have mobile data.
- Android stops searching after two minutes: call `discoverPeers` again every 30 s while not connected.
- On exit: `stopPeerDiscovery` and `removeGroup`.

**Permissions** (in the manifest and requested at runtime): `ACCESS_FINE_LOCATION`, and from Android 13
`NEARBY_WIFI_DEVICES` (with `usesPermissionFlags="neverForLocation"`). Also `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`. The phone's Wi‑Fi and **location must be
on**, or Wi‑Fi Direct fails with "busy".

---

## 3. The language (all little-endian) — `BikeWire.kt`

### PXC messages (knock on 10930 and conversation on 10922)

16-byte header and body, almost always UTF-8 JSON:

```
int command | int length (including the 16) | int command XOR length | int 0
```

The reply carries **the command + 1**. "Not supported" = `0x80000010`.

### RV messages (control on 10921)

8-byte header:

```
short command | short body length | int 0
```

### Image (10920)

The bike sends RV `0x0072` ("give me the next one") and the phone replies **with no RV header**: 4
length bytes (little-endian) and H.264 in Annex-B format (with `00 00 00 01` start codes).
Length 0 = "nothing new". **The first delivery must be SPS + PPS + a full (I) frame together**, and
the same each time the bike reopens this connection (ask the encoder for a full frame).
If there's no new frame within 40 ms, reply with length 0.

---

## 4. The conversation, message by message — `BikeLink.kt`

### Port 10922: two connections

The bike says which is which with its first message, and the reply is the command + 1 with no body.

**`0x00010000` — here the bike asks** and the phone answers:

| Command | What it is | Reply (command + 1) |
|---|---|---|
| `0x00010010` | Introduces itself (JSON with `HUID`, `supportFunction`, `socketTimeoutPeriodWifi`…) | The phone's JSON, see below |
| `0x70000000` | Heartbeat, every second | empty |
| `0x00010450` | What time is it? | `{"time":ms,"currentTime":ms+offset,"currentTimeZone":"Europe/Madrid","dateTime":"dd.MM.yyyy HH:mm:ss:SSS"}` |
| `0x000103E0` | Its serial number (`sn`) to be checked | empty, then `0x000201C0` over the other connection |
| `0x00010040`, `0x00010630`, `0x00010740` | HUD icons, the weather, `{"enabled":1}` | empty |
| `0x000107C0` | Requests TLS encryption (never seen on this bike) | `0x80000010` |
| anything else | — | `0x80000010` |

Odd commands are replies: don't answer them.

**Reply to the introduction** (`BikeLink.greeting`):

```json
{
  "pxcVersion": "1.0.2",
  "phoneUUID": "<made-up and stored uuid>",
  "phoneBrand": "Xiaomi", "phoneModel": "…", "phoneOsVersion": "34", "phoneOs": "Android",
  "package": "net.easyconn.easyride.wws",
  "versionCode": 47,
  "token": 0,
  "pubkey": "<RSA public key, X.509 in base64>",
  "encryptedHUID": "<bike's HUID encrypted with the private key, RSA/ECB/PKCS1Padding, base64>",
  "bluetoothName": "…",
  "supportH264IFrame": true,
  "supportFunction": <the same number the bike sent>,
  "appVersionFingerPrint": "V:2.4(47)-eee8ab8d1-ONLINE",
  "speechEngineType": 2
}
```

The RSA keys (1024 bits) are **made up by the phone** the first time and stored (`BikeKeys.kt`).
They're no EasyConn secret; the bike only returns `encryptedHUID` when asking for an image.

**The app name:** Carbit's (`net.easyconn.easyride.wws`, version 47) definitely works, and it's the
one Motocom uses. With another name, it hasn't been checked that the bike shows the image.

**`0x00020000` — here the phone asks.** Every second, and it also serves as the phone's heartbeat:

```
0x00020020 {"displayRotation":0,"width":<phone width>,"height":<phone height>,
            "enableAccessibility":false,"enableAOAHid":true,"mode":2}
```

`mode 2` = "screen drawn for the bike". After receiving the serial number, send once:

```
0x000201C0 {"isOk":true,"errCode":-100,"errMsg":"Network Not Available","id":"<sn>"}
```

(this is what Carbit answers when it has no internet; the bike carries on happily). If the bike
doesn't answer within `socketTimeoutPeriodWifi` seconds (9), treat the connection as dead.

### Port 10921: image control

| The bike sends | Reply |
|---|---|
| `0x0010` which image it wants (width, height, fps, encoder, bitrate… see `PictureRequest.parse`) | `0x0011` with `int encoder, short width, short height` (width and height rounded down to a multiple of 16: 592 × 816 already are) |
| `0x0060` more details (JSON) | `0x0061` `{"supportFunction":…}` (and `"viewAreaConfig":{"state":0}` if requested) |
| `0x0030` version | `0x0031` with `int 5002, int 1` |
| `0x0040` heartbeat | `0x0041` |
| `0x0070` start | `0x0071` if the encoder is ready; `0x7F05` if not |
| `0x0020` touch / key | nothing (they don't arrive with this bike) |
| `0x0110` console command (`getprop …`) | `0x0111` with the answer and a `\0` |
| other | `0x7F02` |

`fps 0` in the request = "I ask for each frame" (which is what this bike does).

---

## 5. Making the image — `MirrorPicture.kt`

- H.264 `MediaCodec` (`video/avc`) at **592 × 816**, `COLOR_FormatSurface`, the bitrate the bike asks
  for (4 Mbit/s), 30 fps, a full frame every 3 s, and `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100 ms` so
  frames come out even if the screen doesn't change.
- The image is drawn **directly on the encoder's surface**, so the phone's screen needn't be on: it
  works with the phone locked in your pocket. Motocom uses ADB to create a virtual screen the size of
  the bike's that draws on that surface, and moves the chosen app there (`shell/DisplayService.kt`).
  Without ADB, another way is to draw yourself with `surface.lockHardwareCanvas()`, draw and
  `unlockCanvasAndPost()`.
- Keep the SPS/PPS (`BUFFER_FLAG_CODEC_CONFIG` or `csd-0`/`csd-1`) and put it in front of the first
  full frame (`BUFFER_FLAG_KEY_FRAME`). Until you have a full one, discard the others.
- If more than ~60 frames pile up without the bike asking for them, flush and request a full one
  (`PARAMETER_KEY_REQUEST_SYNC_FRAME`).

---

## 6. Keeping it going with the phone locked — `BikeService.kt`

Foreground service of type **`connectedDevice`** (`FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest, and `POST_NOTIFICATIONS`) with a notification
with a "Disconnect" button, plus a `PARTIAL_WAKE_LOCK` (`WAKE_LOCK`) of at most 12 h. If the app uses
GPS with the phone locked, it also needs its own location service.

---

## 7. Reconnecting and giving up — `Moto.kt`

- If the bike's Wi‑Fi is lost (switched off at a stop), close everything, wait 3 s for the ports to be
  released and start again. Search for it for 10 min; on opening, for 3 min. Any longer just wastes
  battery in your pocket.
- Keep a log (`moto_log.txt`) of each attempt with its time, and make it shareable from the phone: the
  bike is in a garage with no computer, and the log is all you'll have to find out what happened.

---

## 8. Important warnings

- **Carbit Ride and the new app can't run at the same time:** they use the same ports. If opening the
  ports gives an error, Carbit Ride is open: close it completely.
- **Don't copy anything from Carbit** into the new app (not its APK, its code or its `service_tsl.p12`
  TLS certificate): it isn't needed.
- **Don't run tests involving uninstalling the app with your everyday phone connected to the
  computer:** that erases maps and routes.

---

## 9. Testing without the bike — `codigo/moto_falsa.py`

It acts as the bike over the USB cable: it says exactly what the real bike said and saves the video
the phone sends. With the new app listening on the phone:

```bash
for p in 10920 10921 10922; do adb forward tcp:$((p+10000)) tcp:$p; done
python3 moto_falsa.py salida.h264 20
ffmpeg -i salida.h264 -frames:v 1 imagen.png
```

(It doesn't do the "knock" in step 3: over the cable it isn't needed, as the fake bike goes straight
to the ports.)

---

## 10. Where each part is

In `app/src/main/java/com/motocom/`:

| File | What it does | Depends on Android |
|---|---|---|
| `bike/BikeWire.kt` | PXC and RV format, image request | No |
| `bike/BikeLink.kt` | The whole conversation and sending the image (needs `kotlinx.serialization.json`) | No |
| `bike/BikeQr.kt` | Reads the bike's QR | No |
| `BikeWifi.kt` | Joins the Wi‑Fi Direct network | Yes |
| `BikeKeys.kt` | The phone's UUID and RSA keys | Yes (preferences) |
| `MirrorPicture.kt` | Encodes the image to H.264 | Yes |
| `BikeService.kt` | Foreground service | Yes |
| `Moto.kt` | Puts it all together: attempts, knock, reconnection, log | Yes |

And in `codigo/moto_falsa.py`, the fake bike for testing over the cable.
