# Resumen de Optimizaciones y Estado de Motocom

Documento generado para referencia en Android Studio y seguimiento del proyecto.

---

## 1. Optimizaciones implementadas en el código

### A. Pipeline Gráfico y OpenGL (`BlackEdges.kt`)
* **Caché de ubicaciones de uniforms y attributes**: Se han precalculado y guardado en variables (`uMatrixLocation`, `aPositionLocation`) las ubicaciones de los sombreadores durante `start()`.
* **Beneficio**: Se eliminan las consultas de búsqueda por nombre de string (`glGetUniformLocation` y `glGetAttribLocation`) en cada fotograma a 30 FPS, reduciendo el overhead JNI y llamadas al driver gráfico.

### B. Codificador de Vídeo por Hardware (`MirrorPicture.kt`)
* **Flags de tiempo real y baja latencia**:
  * `MediaFormat.KEY_PRIORITY = 0`: Prioridad en tiempo real al programador del sistema Android.
  * `MediaFormat.KEY_LATENCY = 0`: Modo de mínima latencia para Android 11+ (API 30+).
  * `format.setInteger("max-bframes", 0)`: Desactiva fotogramas bidireccionales para evitar buffering de reordenación.
* **Optimización de concatenación SPS/PPS**:
  * Se sustituyó `(header ?: ByteArray(0)) + bytes` por una copia directa en memoria mediante `System.arraycopy`, reduciendo asignaciones superfluas de memoria en la recolección de basura (GC).
* **Reducción de la cola de retardo (`BACKLOG`)**:
  * Reducida de 60 fotogramas (2 segundos) a **15 fotogramas (0.5 segundos)**.
  * Si la conexión Wi-Fi de la moto sufre congestión momentánea, la cola descarta frames obsoletos mucho antes y solicita un fotograma clave (`Sync Frame`), evitando desfase de navegación en carretera.

### C. Conexión Wi-Fi Direct (`BikeWifi.kt`)
* **Fallback rápido a credenciales del QR**:
  * `PASSWORD_AFTER_MS` reducido de 25 segundos a **12 segundos**. Si el emparejamiento por botón virtual (PBC) no responde en 12s, conecta inmediatamente con la contraseña guardada del QR.

### D. Reducción y Ofuscación R8 / ProGuard (`build.gradle.kts` y `proguard-rules.pro`)
* Activado `isMinifyEnabled = true` y `isShrinkResources = true` para builds de `release`.
* Creado `proguard-rules.pro` protegiendo:
  * `com.motocom.shell.**` (imprescindible porque ADB lo ejecuta directamente mediante `app_process`).
  * Interfaces AIDL Binder (`IBikeDisplay`, `IShellLog`).
  * Proveedores criptográficos (BouncyCastle, Conscrypt, libadb) y escáner ZXing.

### E. Entorno de compilación en Windows
* Añadido `gradlew.bat` para soporte nativo en CMD y PowerShell.
* Ajustado `jvmToolchain(21)` en `app/build.gradle.kts` para aprovechar el JDK 21 instalado en el sistema.
* Configurado `local.properties` con la ruta al SDK de Android (`C:\Users\oscar\AppData\Local\Android\Sdk`).

---

## 2. Pruebas sin moto (Simulador `moto_falsa.py`)

Para probar la app desde el PC sin la moto:
1. En el móvil o emulador, abrir Motocom y pulsar **Conectar**.
2. En la terminal del PC:
   ```powershell
   adb forward tcp:20920 tcp:10920
   adb forward tcp:20921 tcp:10921
   adb forward tcp:20922 tcp:10922
   python codigo/moto_falsa.py salida.h264 5
   ```
3. Comprobar el vídeo generado con VLC, mpv o ffplay:
   ```powershell
   ffplay -f h264 salida.h264
   ```
