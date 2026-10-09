# Motocom: instalación y uso

La app actual muestra otras apps en la TFT y usa **ADB directo, sin Shizuku**. Consulta [la guía de conexión de Motocom 0.3](GUIA-CONEXION.md). La explicación que sigue documenta el protocolo de la moto y el código original de referencia.

# Cómo se conecta el móvil a la pantalla de la moto

Guía para llevar a **otro proyecto** la conexión directa con la pantalla TFT de la moto,
sin Carbit Ride. Sacada de OffNavigator (versión 16.9, 08-10-2026), donde **funciona con la moto de
verdad** desde el 30-09-2026. La historia completa de cómo se averiguó está en `PANTALLA-MOTO.md`, en
la raíz de OffNavigator.

**En corto:** el móvil entra en el wifi propio de la moto (sin Bluetooth ni internet),
le avisa de que está ahí, la moto se conecta al móvil, le pide un vídeo del tamaño de su pantalla y
el móvil se lo va mandando. Lo que se vea en la moto es lo que la app dibuje en ese vídeo.

**Para Claude en el otro proyecto:** en `codigo/` hay copia del código que ya funciona. Lo de la
conversación (`BikeWire`, `BikeLink`, `BikeQr`) es Kotlin puro y se puede usar tal cual; lo de
Android (`BikeWifi`, `BikeKeys`, `BikeEncoder`, `BikeService`, `BikeScreen`) hay que adaptarlo
(paquetes, preferencias, y la vista que se pinta). Leer esta guía primero; el código lo confirma.

---

## 1. La moto

- Pantalla EasyConn (la misma que usa Carbit Ride, `net.easyconn.easyride.wws`).
- Wifi directo: **`HJ-5G-XXXXXX`** (como red normal, `DIRECT-HJ-5G-XXXXXX`, con la contraseña que dice el QR).
- HUID `XXXXXXXXXXXX`, `sdkVersion 1.0.14`, `pxcVersion 1.0.2`, `flavor 51`, `channel "37512"`.
- Pide imagen de **592 × 816** (vertical), H.264, 4 Mbit/s, y pide las imágenes una a una.
- **No es táctil** y sus botones no mandan nada útil al móvil (solo cambian lo que enseña la moto).
- **No pide licencia ni cifrado.** No hace falta internet ni ningún secreto de EasyConn.
- Ya tiene su propia franja con velocidad, revoluciones, temperatura y gasolina: no hace falta
  repetirlas.

### El QR de la pantalla de la moto

La moto enseña un QR que no es una llave, solo dice cómo se llama su wifi:

```
http://www.carbit.com.cn/down6/…?modelid=37512&sn=XXXX&action=9
    &ssid=DIRECT-HJ-5G-XXXXXX&pwd=xxxxxxxx&auth=WPA-PSK&name=HJ-5G-XXXXXX
```

`BikeQr.parse()` lo lee. Con QR se busca el aparato con ese nombre exacto; sin QR, cualquiera que
empiece por `HJ-`. Para una app para una sola moto se pueden poner los datos fijos y olvidarse del QR.

---

## 2. Los pasos, en orden

```
 móvil                                             moto (192.168.13.1)
 ─────                                             ────
 1. abre sus puertos 10920, 10921, 10922
 2. entra en el wifi directo de la moto  ───────►  (la moto es la jefa del grupo)
    el móvil queda en 192.168.13.x
 3. «llama»: TCP a 192.168.13.1:10930   ───────►
    PXC 0x70000010 {"phoneType":"Android",
                    "packageName":"…"}
                                        ◄───────   0x70000011 {"status":"true"}
 4.                                     ◄───────   la moto abre 4 conexiones AL MÓVIL:
                                                   2 al 10922 (conversación)
                                                   1 al 10921 (control de imagen)
                                                   1 al 10920 (imagen)
 5. contesta a todo y manda H.264 cada vez que la moto pide imagen
```

Lo importante: **el móvil es el servidor**. Sin la «llamada» del paso 3 la moto no hace nada. La
primera llamada puede llegar demasiado pronto: se repite cada 3 s hasta 10 veces, hasta que la moto
se presente (`BikeScreen.knockUntilHeard`).

### Paso 2: el wifi directo (`BikeWifi.kt`)

- `WifiP2pManager.discoverPeers()`, y en `WIFI_P2P_PEERS_CHANGED_ACTION` buscar el aparato por nombre.
- Conectar como Carbit: `WifiP2pConfig` con `wps.setup = WpsInfo.PBC` y **`groupOwnerIntent = 0`**
  (la moto tiene que ser la jefa del grupo; si el móvil queda de jefe, no se sabe la dirección de la
  moto). Carbit tardó unos 7 s; la nuestra, 2 s.
- Si a los 25 s sigue fuera, plan B (Android 10+): `WifiP2pConfig.Builder().setNetworkName(ssid)
  .setPassphrase(contraseña del QR)`.
- En `WIFI_P2P_CONNECTION_CHANGED_ACTION` → `requestConnectionInfo`: si `groupFormed` y no somos
  jefes, `groupOwnerAddress` es la moto. La dirección propia del móvil es la IPv4 de sus interfaces
  que comparte los tres primeros números con la moto; hay que **atar la llamada a esa dirección**
  (`socket.bind`) porque el móvil puede tener también datos móviles.
- Android deja de buscar a los dos minutos: volver a llamar a `discoverPeers` cada 30 s mientras no
  se esté dentro.
- Al salir: `stopPeerDiscovery` y `removeGroup`.

**Permisos** (en el manifiesto y pedidos en marcha): `ACCESS_FINE_LOCATION`, y desde Android 13
`NEARBY_WIFI_DEVICES` (con `usesPermissionFlags="neverForLocation"`). Además `ACCESS_WIFI_STATE`,
`CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`. El wifi y la **ubicación del móvil tienen
que estar encendidos**, o el wifi directo falla con «ocupado».

---

## 3. El idioma (todo en little-endian) — `BikeWire.kt`

### Mensajes PXC (llamada en 10930 y conversación en 10922)

Cabecera de 16 bytes y cuerpo, casi siempre JSON en UTF-8:

```
int orden | int longitud (con los 16) | int orden XOR longitud | int 0
```

La respuesta lleva **la orden + 1**. «No lo admito» = `0x80000010`.

### Mensajes RV (control en 10921)

Cabecera de 8 bytes:

```
short orden | short longitud del cuerpo | int 0
```

### Imagen (10920)

La moto manda RV `0x0072` («dame la siguiente») y el móvil contesta **sin cabecera RV**: 4 bytes de
longitud (little-endian) y el H.264 en formato Annex-B (con códigos de inicio `00 00 00 01`).
Longitud 0 = «nada nuevo». **La primera entrega tiene que ser SPS + PPS + imagen completa (I) juntos**,
y cada vez que la moto vuelve a abrir esta conexión, lo mismo (pedir imagen completa al codificador).
Si no hay imagen nueva en 40 ms, contestar longitud 0.

---

## 4. La conversación, mensaje a mensaje — `BikeLink.kt`

### Puerto 10922: dos conexiones

La moto dice cuál es cuál con su primer mensaje, y se le contesta con la orden + 1 sin cuerpo.

**`0x00010000` — aquí pregunta la moto** y el móvil contesta:

| Orden | Qué es | Respuesta (orden + 1) |
|---|---|---|
| `0x00010010` | Se presenta (JSON con `HUID`, `supportFunction`, `socketTimeoutPeriodWifi`…) | JSON del móvil, ver abajo |
| `0x70000000` | Latido, cada segundo | vacía |
| `0x00010450` | ¿Qué hora es? | `{"time":ms,"currentTime":ms+desfase,"currentTimeZone":"Europe/Madrid","dateTime":"dd.MM.yyyy HH:mm:ss:SSS"}` |
| `0x000103E0` | Su número de serie (`sn`) para comprobarlo | vacía, y después por la otra conexión `0x000201C0` |
| `0x00010040`, `0x00010630`, `0x00010740` | Iconos del HUD, el tiempo, `{"enabled":1}` | vacía |
| `0x000107C0` | Pedir cifrado TLS (nunca visto en esta moto) | `0x80000010` |
| cualquier otra | — | `0x80000010` |

Las órdenes impares son respuestas: no contestarlas.

**Respuesta a la presentación** (`BikeLink.greeting`):

```json
{
  "pxcVersion": "1.0.2",
  "phoneUUID": "<uuid inventado y guardado>",
  "phoneBrand": "Xiaomi", "phoneModel": "…", "phoneOsVersion": "34", "phoneOs": "Android",
  "package": "net.easyconn.easyride.wws",
  "versionCode": 47,
  "token": 0,
  "pubkey": "<clave pública RSA, X.509 en base64>",
  "encryptedHUID": "<HUID de la moto cifrado con la clave privada, RSA/ECB/PKCS1Padding, base64>",
  "bluetoothName": "…",
  "supportH264IFrame": true,
  "supportFunction": <el mismo número que mandó la moto>,
  "appVersionFingerPrint": "V:2.4(47)-eee8ab8d1-ONLINE",
  "speechEngineType": 2
}
```

Las claves RSA (1024 bits) **se las inventa el móvil** la primera vez y las guarda (`BikeKeys.kt`).
No son ningún secreto de EasyConn; la moto solo devuelve `encryptedHUID` al pedir imagen.

**El nombre de la app:** con el de Carbit (`net.easyconn.easyride.wws`, versión 47) funciona seguro.
OffNavigator prueba primero con su propio nombre y, si en 45 s dentro del wifi la moto no enseña
nada, vuelve al de Carbit. En las notas no consta todavía cuál aceptó la moto de verdad. Para la app
nueva, lo seguro es presentarse como Carbit; si se quiere el nombre propio, copiar ese mecanismo.

**`0x00020000` — aquí pregunta el móvil.** Cada segundo, y sirve también de latido del móvil:

```
0x00020020 {"displayRotation":0,"width":<ancho móvil>,"height":<alto móvil>,
            "enableAccessibility":false,"enableAOAHid":true,"mode":2}
```

`mode 2` = «pantalla dibujada para la moto». Tras recibir el número de serie, mandar una vez:

```
0x000201C0 {"isOk":true,"errCode":-100,"errMsg":"Network Not Available","id":"<sn>"}
```

(es lo que contesta Carbit cuando no tiene internet; la moto sigue tan contenta). Si la moto no
contesta en `socketTimeoutPeriodWifi` segundos (9), dar la conexión por muerta.

### Puerto 10921: control de la imagen

| La moto manda | Respuesta |
|---|---|
| `0x0010` qué imagen quiere (ancho, alto, fps, codificador, bitrate… ver `PictureRequest.parse`) | `0x0011` con `int codificador, short ancho, short alto` (ancho y alto redondeados a múltiplo de 16 hacia abajo: 592 × 816 ya lo son) |
| `0x0060` más detalles (JSON) | `0x0061` `{"supportFunction":…}` (y `"viewAreaConfig":{"state":0}` si lo pidió) |
| `0x0030` versión | `0x0031` con `int 5002, int 1` |
| `0x0040` latido | `0x0041` |
| `0x0070` empezar | `0x0071` si el codificador está listo; `0x7F05` si no |
| `0x0020` pulsación / tecla | nada (no llegan con esta moto) |
| `0x0110` orden de consola (`getprop …`) | `0x0111` con la respuesta y un `\0` |
| otra | `0x7F02` |

`fps 0` en la petición = «yo pido cada imagen» (es lo que hace esta moto).

---

## 5. Hacer la imagen — `BikeEncoder.kt`

- `MediaCodec` H.264 (`video/avc`) de **592 × 816**, `COLOR_FormatSurface`, el bitrate que pide la
  moto (4 Mbit/s), 30 fps, imagen completa cada 3 s, y `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100 ms`
  para que salgan imágenes aunque la pantalla no cambie.
- Se pinta **directamente en la superficie del codificador**: `surface.lockHardwareCanvas()`,
  dibujar, `unlockCanvasAndPost()`, unas **15 veces por segundo**. Así no hace falta ventana ni que
  la pantalla del móvil esté encendida: funciona con el móvil bloqueado en el bolsillo.
- Guardar el SPS/PPS (`BUFFER_FLAG_CODEC_CONFIG` o `csd-0`/`csd-1`) y pegarlo delante de la primera
  imagen completa (`BUFFER_FLAG_KEY_FRAME`). Hasta tener una completa, tirar las otras.
- Si se acumulan más de ~60 imágenes sin que la moto las pida, vaciar y pedir una completa
  (`PARAMETER_KEY_REQUEST_SYNC_FRAME`).
- En OffNavigator lo que se pinta es `BikeView` (mapa girado, ruta, franja con datos). En la app nueva
  se sustituye por lo que se quiera dibujar en un `Canvas` de 592 × 816. `BikeEncoder.kt` está copiado
  tal cual y por eso nombra `BikeView` y `BikeLayout`, que **no** están en esta carpeta.

---

## 6. Que siga con el móvil bloqueado — `BikeService.kt`

Servicio en primer plano de tipo **`connectedDevice`** (`FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_CONNECTED_DEVICE` en el manifiesto, y `POST_NOTIFICATIONS`) con un aviso con botón
«Desconectar», más un `PARTIAL_WAKE_LOCK` (`WAKE_LOCK`) de 12 h como mucho. Si la app usa el GPS
con el móvil bloqueado, necesita además su propio servicio de ubicación.

---

## 7. Reconectar y rendirse — `BikeScreen.kt`

- Si se pierde el wifi de la moto (apagada en una parada), cerrar todo, esperar 3 s a que se suelten
  los puertos y empezar otra vez. Buscarla durante 10 min; al abrir, durante 3 min. Más tiempo gasta
  batería en el bolsillo para nada.
- Guardar un registro (`moto_log.txt`) de cada intento con hora, y poder compartirlo desde el móvil:
  la moto está en un garaje sin ordenador, y el registro es lo único que habrá para saber qué pasó.

---

## 8. Avisos importantes

- **Carbit Ride y la app nueva no pueden estar a la vez:** usan los mismos puertos. Si al abrir los
  puertos da error, Carbit (u OffNavigator) está abierto: cerrarlo del todo. Lo mismo entre
  OffNavigator y la app nueva.
- **No copiar nada de Carbit** a la app nueva (ni su APK, ni su código, ni su certificado TLS
  `service_tsl.p12`): no hace falta.
- **No hacer pruebas con el móvil conectado al ordenador en el móvil de uso diario** que impliquen
  desinstalar la app: se borran mapas y rutas.

---

## 9. Probar sin la moto — `codigo/moto_falsa.py`

Hace de moto por el cable USB: dice exactamente lo que dijo la moto de verdad y guarda el vídeo que
manda el móvil. Con la app nueva escuchando en el móvil:

```bash
for p in 10920 10921 10922; do adb forward tcp:$((p+10000)) tcp:$p; done
python3 moto_falsa.py salida.h264 20
ffmpeg -i salida.h264 -frames:v 1 imagen.png
```

(No hace la «llamada» del paso 3: con el cable no hace falta, la moto falsa va directa a los puertos.)

---

## 10. Qué hay en `codigo/`

| Archivo | Qué hace | Depende de Android |
|---|---|---|
| `BikeWire.kt` | Formato PXC y RV, petición de imagen | No |
| `BikeLink.kt` | Toda la conversación y el envío de imagen (necesita `kotlinx.serialization.json`) | No |
| `BikeQr.kt` | Leer el QR de la moto | No |
| `BikeWifi.kt` | Entrar en el wifi directo | Sí |
| `BikeKeys.kt` | UUID y claves RSA del móvil | Sí (preferencias) |
| `BikeEncoder.kt` | Pintar y codificar a H.264 | Sí |
| `BikeService.kt` | Servicio en primer plano | Sí |
| `BikeScreen.kt` | Lo junta todo: intentos, llamada, reconexión, registro | Sí (y cosas de OffNavigator) |
| `moto_falsa.py` | Moto falsa para probar por cable | — |

Son copias del 08-10-2026; las de verdad siguen en OffNavigator
(`core/src/main/kotlin/com/offnavigator/core/bike/` y `app/src/main/java/com/offnavigator/bike/`).
