# Motocom

App Android que manda la imagen de otra app del móvil (Waze, Google Maps u otra) a la pantalla TFT
EasyConn de la moto, sin usar Carbit Ride ni internet.

El móvil entra en el wifi directo de la pantalla de la moto, crea una pantalla virtual de 592 × 816
y le envía en vídeo H.264 la app elegida. Esa app se puede traer al móvil para escribir un destino y
devolver después a la moto.

La app está en español y en inglés: el idioma se elige abajo en la pantalla principal (por
defecto, español).

> **Proyecto no oficial.** No tiene relación con ningún fabricante de motos, ni con EasyConn ni Carbit. Solo se ha probado con
> una moto y unos pocos móviles. Úsalo bajo tu responsabilidad y prueba siempre con la moto parada.

## Instalar

1. Descarga el último `motocom-vX.Y.apk` desde
   [Releases](https://github.com/Jacks1203/Motocom/releases) en el móvil.
2. Ábrelo y permite instalar apps de origen desconocido si Android lo pide.
3. Sigue la [guía de conexión](GUIA-CONEXION.md): la primera vez hay que activar la depuración
   inalámbrica y emparejar Motocom consigo misma (no hace falta ordenador ni Shizuku).

Desde la 0.7 el APK va firmado con la clave propia de Motocom. Si tenías la 0.6 o una anterior,
desinstálala antes de instalar la 0.7; después las actualizaciones se instalan encima.

Requisitos: Android 10 o superior (Android 11 para emparejar sin ordenador; Android 13 para que la
imagen siga con el móvil bloqueado).

## Por qué necesita ADB

Para que Waze o Maps salgan en la moto, Motocom crea una segunda pantalla del tamaño de la de la
moto, abre en ella la app elegida y la mueve entre esa pantalla y la del móvil. Android no deja
que una app normal abra otras apps en una pantalla creada por ella: eso solo lo puede hacer el
usuario «shell», el mismo que usa un ordenador conectado con `adb` (y apps como scrcpy).

Por eso Motocom usa la **depuración inalámbrica** de Android para conectarse por ADB **al propio
móvil** (`127.0.0.1`), sin root, sin ordenador y sin Shizuku:

- El emparejamiento con el código autoriza solo la clave de Motocom en ese móvil. Se hace una vez.
- Por ADB, Motocom arranca un pequeño proceso suyo con permisos de shell. Ese proceso solo hace
  cuatro cosas: crear la pantalla de la moto, llevar la app elegida a ella o de vuelta al móvil,
  quitar esa pantalla y cerrarse. Solo atiende a Motocom.
- La primera vez, Motocom se concede por ADB el permiso de cambiar ajustes del sistema
  (`WRITE_SECURE_SETTINGS`), solo para poder encender y apagar ella misma la depuración
  inalámbrica las siguientes veces.
- Nada sale a internet: ADB va por dentro del propio móvil.

Ese proceso sigue en marcha hasta que reinicias el móvil, actualizas o desinstalas Motocom. Para
retirar la autorización, entra en Opciones de desarrollador → «Revocar autorizaciones de depuración»
y reinicia el móvil. El permiso `WRITE_SECURE_SETTINGS` sigue concedido hasta desinstalar Motocom.

En algunos Android 14 o superiores (como Android 17 en Pixel), la pantalla de la moto se apagaría
con la del móvil. Ahí el proceso la crea como «dispositivo conectado», igual que las pantallas
que se mandan a un coche, y para eso empareja con el sistema un dispositivo ficticio «Moto».
Ese emparejamiento se quita al desconectar.

## Permisos

| Permiso | Para qué lo usa Motocom |
|---|---|
| Cámara | Leer el código QR de la pantalla de la moto (nombre de su wifi y contraseña). Solo mientras lees el QR. |

Los puertos de Motocom solo aceptan conexiones de la pantalla de la moto, no de otros aparatos del
wifi en que esté el móvil.
| Dispositivos wifi cercanos (Android 13+) | Encontrar el wifi directo de la pantalla de la moto. Está declarado como «no se usa para la ubicación». Imprescindible. |
| Ubicación (solo Android 12 o anterior) | Esos Android la exigen para buscar dispositivos por wifi directo. Motocom no lee ni guarda tu ubicación. En Android 13+ no se pide. |
| Ver y cambiar el wifi y la red | Conectarse al wifi directo de la moto. |
| Acceso a la red (`INTERNET`) | Hablar con la moto por su wifi y con ADB dentro del propio móvil. Motocom no usa internet (la app de mapas sí puede usarlo). |
| Notificaciones | Mostrar la notificación «Pantalla de la moto» mientras manda la imagen. Opcional: sin ella funciona igual. |
| Servicio en primer plano (dispositivo conectado) | Seguir mandando la imagen con la app en segundo plano o el móvil bloqueado. |
| Mantener el móvil activo (`WAKE_LOCK`) | Que el móvil no se duerma mientras manda la imagen con la pantalla apagada. |
| Pedir quitar la optimización de batería | Ofrecer el botón «Permitir» para que Android no corte la imagen con el móvil bloqueado. Es opcional. |
| Cambiar ajustes del sistema (`WRITE_SECURE_SETTINGS`) | Encender y apagar sola la depuración inalámbrica. No se puede pedir desde la app: se lo concede ella misma por ADB (ver arriba). |
| Ver las apps instaladas | Mostrar la lista para elegir qué app va a la moto. |

Motocom no tiene anuncios ni analíticas y no manda datos a ningún servidor. El registro de cada
conexión se guarda solo en el móvil, y solo sale de él si tú lo compartes desde la pantalla
Registro.

## Compilar

Con el SDK de Android instalado y `local.properties` apuntando a él (`sdk.dir=…`):

```
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. La versión publicada se hace con
`./gradlew assembleRelease` (sin `-PcableTests=true`, que solo sirve para probar con
`codigo/moto_falsa.py` por el cable) y necesita la clave de firma, que no está en el repositorio
(`keystore.properties` con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`).

## Documentación

- [GUIA-CONEXION.md](GUIA-CONEXION.md): instalación y uso paso a paso.
- [LEEME.md](LEEME.md): cómo funciona el protocolo de la pantalla de la moto.
- `codigo/`: código de referencia original de la conexión con la moto y `moto_falsa.py`, un
  simulador para hacer pruebas sin moto.

## Licencia

[MIT](LICENSE)
