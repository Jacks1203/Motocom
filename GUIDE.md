# Motocom connection guide

**English** · [Español](GUIA-CONEXION.md)

Motocom connects the phone to the Wi‑Fi Direct network of the bike's EasyConn screen, creates a 592 × 816 virtual display and sends H.264 video of the chosen app. **It works with any navigation app, not just Waze and Google Maps.** You can bring that app to the phone to type a destination and send it back to the bike. The size setting adjusts how big text and buttons look.

ADB gives the display process the Android permissions it needs to open other apps on that display (more detail in [Why it needs ADB](README.en.md#why-it-needs-adb)). The ADB client is built in: you don't need to install or start Shizuku. Pairing authorizes only Motocom's identity on this phone; it can't get around Android's authorization.

Menu names in Android Settings change from one manufacturer to another, so yours may be slightly different. Motocom's labels below are those of its English version: choose English at the bottom of the main screen.

## What you need

- Android 11 or later to pair from the phone itself, without a computer. Android 10 has a USB alternative at the end of this guide.
- A Wi‑Fi network for the phone while you turn on wireless debugging (at home, or another phone's hotspot).
- Motocom and your navigation app installed.
- Android 13 or later to keep the bike's image going while the phone is locked. The manufacturer and the navigation app may add restrictions: check this with the bike stopped.

## 1. Turn on Developer options

In Settings → About phone, tap **Build number** seven times; some manufacturers put it under Software information or use the name of their system version. Enter your PIN if asked.

Connect the phone to a Wi‑Fi network. In **Developer options**, turn on **Wireless debugging** and accept the network. In Motocom, tap the ADB indicator at the top right (it says **Set up ADB**) to open the **ADB** screen, which has an **Open Developer options** button.

## 2. Pair once

### The easy way: from the notification

Split screen isn't needed, so this works on any phone.

1. On Motocom's **ADB** screen, tap **Pair** in the **Pair from the notification** block. If asked, allow notifications: you type the code in one.
2. Developer options open. Go into **Wireless debugging** and tap **Pair device with pairing code** (the name differs on Xiaomi, Redmi and POCO).
3. Motocom finds the port by itself and shows a **Pair Motocom** notification. Without closing Android's dialog, pull down the notification bar and tap **Enter code** on that notification. If it appears collapsed and you can't see the button, expand it with its arrow.
4. Type the six digits and send them. Motocom pairs and starts ADB without you copying any port. When the notification says ADB is ready, go back to Motocom; you can skip section 3.

If pulling down the bar closes the code dialog (this happens on some phones), open another one: the notification updates by itself. If it doesn't work, use the manual method below.

### By hand: putting Motocom and Settings in split screen

**Split screen** means seeing two apps at once, one above the other. That way you can read the code in Settings and type it in Motocom without closing the dialog.

**On Xiaomi, Redmi or POCO (MIUI / HyperOS), try these steps:**

1. Open **Motocom**. Then open **Settings → Developer options → Wireless debugging**. Don't open the code dialog yet.
2. Open **Recent apps**, the view where open apps appear as cards:
   - If you use **gestures**, swipe from the bottom edge towards the centre and hold for a moment before letting go. A quick swipe usually takes you Home.
   - If you have **three buttons** at the bottom, tap Recents (usually a square or three lines).
3. Find the **Motocom** card and **press and hold it** until a menu appears.
4. Tap **Split screen**, or the icon showing **two rectangles, one above the other**. Depending on the version, it may appear when you tap the app's icon above its card.
5. The phone asks for the second app: tap **Settings**. You should see Motocom in one half and Settings in the other; the order doesn't matter.
6. In the Settings half, go to **Wireless debugging → Pair device with pairing code**. You can now enter the port and code in the other half and tap **Pair**.

**On other Android phones**, open Recents, tap the **Motocom icon above its card**, choose **Split screen** or similar, and select Settings as the second app. The name and location vary by phone.

**If space is tight**, drag the bar between the two halves to give Motocom more room and scroll inside the app to the fields. Keep Settings' code dialog open while you pair.

**To exit**, after pairing, drag the central bar to the top or bottom edge. One of the apps goes back to full screen.

**If the option doesn't appear**, check that you're in Recents and not holding down the icon on the home screen. Some models or apps don't support split screen. If your phone has a **floating window**, you can open Motocom that way over Settings to keep both visible; this also depends on the manufacturer.

### Entering the code

1. Open Motocom on its **ADB** screen (the indicator at the top right) and Settings in **split screen**. It's important to keep Android's pairing dialog open; if you close it, the code and port may expire.
2. In Wireless debugging, tap **Pair device with pairing code**.
3. Android shows an address like `192.168.1.20:37123` and a six-digit code. In Motocom's pairing step, enter **37123** in **Pairing port** and the six digits in **6-digit code**. These numbers are examples: use the ones on your phone.
4. Tap **Pair** and wait until the panel at the top says **Paired**.

Motocom connects to `127.0.0.1`, that is, to the phone itself. You don't have to enter the IP or connect to another device. The identity is stored in the app's private storage and isn't included in backups. The pairing code isn't stored.

## 3. Start ADB

1. Close the code dialog and go back to the main **Wireless debugging** screen.
2. Look at **IP address & Port**. The number after `:` is the **connection port**, usually different from the pairing one.
3. Enter it in **Connection port** and tap **Start ADB**.
4. Wait until ADB shows as on, then tap **Back to the bike**.

From then on, the display process talks to the app through Binder, with no network ADB session. You can turn off wireless debugging once it has started. Changing Wi‑Fi doesn't require pairing again. The process stays alive even if Android closes Motocom: when you reopen the app it reattaches by itself, without Wi‑Fi. You only need to start it again after restarting the phone or reinstalling the app. It only takes orders from Motocom.

## 4. Connect the bike

1. Fully close Carbit Ride: it uses the same ports as Motocom.
2. Switch on the bike's screen and open its phone connection function.
3. Turn on **Wi‑Fi** on the phone (and **location** if you have Android 12 or earlier).
4. In Motocom, tap **Scan the bike's QR** and point at the code on its screen (the first time, it asks for camera permission). You only need to do this once; afterwards the bike appears in the **Bike** row. Scanning the QR doesn't connect yet.
5. In the **App** row, choose the app you want to show. Any navigation app installed on the phone will do.
6. Under **Size on the bike**, choose the size of text and buttons. The preview shows how much map fits. **Choose it before connecting: it can't be changed afterwards.**
7. Tap **Connect** and accept the requested permissions: **Nearby devices** (or **Precise location** on Android 12 or earlier) is essential to find the bike; **notifications** are optional. If you deny the essential one, Motocom tells you and takes you to Settings. The app searches over Wi‑Fi Direct for the bike it read from the QR.
8. When the image arrives, the panel at the top says **On the bike**. To type a destination, tap **To the phone** (the panel changes to **The app is on the phone**) and, when you finish, **To the bike**. If the bike reconnects while you're typing, the app isn't taken away from the phone. Check with the bike stopped what happens when you lock the phone before using it on the road.

The video connection with the bike doesn't need internet. Maps or navigation services may need mobile data or downloaded maps. If Motocom warns about battery saving, tap **Allow**: it helps keep the image going while the phone is locked.

## The next times

- Just tap **Connect**: if ADB is stopped, Motocom starts it by itself (if Android lets it turn on wireless debugging; if not, turn it on yourself first). On Xiaomi, Redmi or POCO, for it to do this by itself, turn on **USB debugging (Security settings)** in Developer options before the first **Start ADB**. If ADB doesn't start within 3 minutes, Motocom stops waiting.
- If Android closes Motocom or you force-stop it, the ADB process stays alive: when you open the app, **ADB on** comes back within a second or two.
- After restarting the phone or reinstalling the app: turn on wireless debugging and repeat **Start ADB**. Away from home, without Wi‑Fi, see the next section.
- You don't usually need to repeat **Pair**. Do it if Android has forgotten the identity, or you've cleared Motocom's data or reinstalled the app. Android may revoke inactive authorizations.
- **Disconnect** stops the video, releases the virtual display and ends the search for the bike. The ADB process stays available until you restart the phone or update the app.
- If the bike's screen ends the conversation without turning off its Wi‑Fi, Motocom calls it again by itself.

## Away from home, without Wi‑Fi

If it already said **ADB on**, you don't need to do anything: ADB keeps running until you restart the phone, even if Android closes Motocom. If the phone has been restarted or switched off, use the bike's Wi‑Fi:

1. Switch on the bike and open the phone connection on its screen.
2. Scan the QR on the bike's screen with Motocom (it doesn't need ADB). Tap the **Bike** row on the main screen: **Wi‑Fi** shows the name of the bike's network and **Password** its password.
3. In **Settings → Wi‑Fi**, connect to that network with that password, like any Wi‑Fi. If it warns there's no internet, stay connected anyway.
4. In Motocom, on the **ADB** screen, tap **Start ADB** and wait for **ADB on**. If Android asks whether to allow debugging on that network, accept.
5. **Important:** go back to **Settings → Wi‑Fi**, tap that network and tap **Forget**. If you don't forget it, the bike's screen won't connect.
6. Go back to Motocom and tap **Connect**.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Won't pair | Keep Android's dialog visible and use its port and the new six-digit code. |
| Pairs but won't connect | Use the port from the main screen, not the code's. Check that wireless debugging is still on. |
| Connection refused | The port changes when you turn off debugging, change network or restart. Copy the current port again. |
| ADB connects but the display doesn't start | Keep Motocom open and try again. There may be a manufacturer restriction; share the log. For diagnosis from a computer: `adb logcat -d -s MotocomADB`. |
| Can't find the bike | Wi‑Fi on (and location on Android 12 or earlier), nearby devices permission granted and connection function open on the TFT. |
| The bike doesn't get video | Close Carbit Ride and connect again. If the panel says **The app didn't reach the bike**, tap **To the bike**. Share Motocom's log. |
| Stops when locked | Check battery saving, the Android version and the restrictions of the chosen app. |

To save the details of an attempt, tap the **Log** icon at the top right and then **Share log**. If a pairing gets no response, fully close Motocom and try again with a new code dialog.

## Android 10: setup from a computer

Android 10 doesn't offer wireless pairing with a code. Connect the phone to the computer by USB, turn on **USB debugging** and accept the authorization. With the official Android Platform Tools:

```sh
adb devices
adb tcpip 5555
```

In Motocom, enter **5555** as the connection port, tap **Start ADB** and accept the authorization for the new key on the phone if it appears; try again if the authorization takes longer than the connection. Don't use the pairing fields. Once ADB is on, you can unplug the cable. This setup is lost on restart. To close the TCP port later, connect by USB and run `adb usb`.
