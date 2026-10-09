# Motocom

App Android que manda la imagen de otra app del móvil (Waze, Google Maps u otra) a la pantalla TFT
EasyConn de la moto, sin usar Carbit Ride ni internet.

El móvil entra en el wifi directo de la pantalla de la moto, crea una pantalla virtual de 592 × 816
y le envía en vídeo H.264 la app elegida. Esa app se puede traer al móvil para escribir un destino y
devolver después a la moto.

> **Proyecto no oficial.** No tiene relación con ningún fabricante de motos, ni con EasyConn ni Carbit. Solo se ha probado con
> una moto y unos pocos móviles. Úsalo bajo tu responsabilidad y prueba siempre con la moto parada.

## Instalar

1. Descarga el último `motocom-vX.Y.apk` desde
   [Releases](https://github.com/Jacks1203/Motocom/releases) en el móvil.
2. Ábrelo y permite instalar apps de origen desconocido si Android lo pide.
3. Sigue la [guía de conexión](GUIA-CONEXION.md): la primera vez hay que activar la depuración
   inalámbrica y emparejar Motocom consigo misma (no hace falta ordenador ni Shizuku).

Requisitos: Android 10 o superior (Android 11 para emparejar sin ordenador; Android 13 para que la
imagen siga con el móvil bloqueado).

## Compilar

Con el SDK de Android instalado y `local.properties` apuntando a él (`sdk.dir=…`):

```
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

## Documentación

- [GUIA-CONEXION.md](GUIA-CONEXION.md): instalación y uso paso a paso.
- [LEEME.md](LEEME.md): cómo funciona el protocolo de la pantalla de la moto.
- `codigo/`: código de referencia original de la conexión con la moto y `moto_falsa.py`, un
  simulador para hacer pruebas sin moto.

## Licencia

[MIT](LICENSE)
