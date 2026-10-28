# Guía de conexión de Motocom

Motocom conecta el móvil al wifi directo de la pantalla EasyConn de la moto, crea una pantalla virtual de 592 × 816 y envía vídeo H.264 de la app elegida (por ejemplo Waze o Google Maps). Puedes traer esa app al móvil para escribir un destino y devolverla a la moto. La densidad ajusta el tamaño de las letras.

ADB da al proceso de pantalla los permisos de Android necesarios para abrir otras apps en ella (más detalle en [Por qué necesita ADB](README.md#por-qué-necesita-adb)). Esta versión integra el cliente ADB: no necesitas instalar ni iniciar Shizuku. El emparejamiento autoriza únicamente la identidad de Motocom en este móvil; no permite saltarse la autorización de Android.

## Lo que necesitas

- Android 11 o superior para emparejar desde el propio móvil, sin ordenador. Android 10 tiene una alternativa por USB al final de esta guía.
- Un wifi al que conectar el móvil durante la activación de la depuración inalámbrica (casa o punto de acceso de otro teléfono).
- Motocom instalada y la app de navegación elegida.
- Para mantener la pantalla independiente del bloqueo, Android 13 o superior. El fabricante y la app de navegación pueden imponer restricciones: compruébalo con la moto parada.

## 1. Activar las opciones de desarrollador

En Ajustes → Acerca del teléfono, toca siete veces **Número de compilación**; algunos fabricantes lo sitúan en Información de software o usan el nombre de su versión del sistema. Introduce el PIN si lo pide.

Conecta el móvil a un wifi. En **Opciones de desarrollador**, activa **Depuración inalámbrica** y acepta la red. En Motocom, toca el indicador de ADB de arriba a la derecha (pone **Configurar ADB**): se abre la pantalla **ADB**, que tiene un botón para abrir las opciones de desarrollador.

## 2. Emparejar una vez

### La forma fácil: desde la notificación

No hace falta la pantalla dividida, así que vale para cualquier móvil.

1. En la pantalla **ADB** de Motocom, pulsa **Emparejar** en el bloque **Emparejar desde la notificación**. Si lo pide, permite las notificaciones: el código se escribe en una.
2. Se abren las opciones de desarrollador. Entra en **Depuración inalámbrica** y toca **Vincular dispositivo con código** (en Xiaomi, Redmi o POCO se llama **Emparejar dispositivo con código de sincronización**).
3. Motocom encuentra sola el puerto y muestra un aviso **Emparejar Motocom**. Sin cerrar el diálogo de Android, baja la barra de notificaciones y toca **Escribir código** en ese aviso. Si el aviso sale plegado y no ves el botón, despliégalo con su flecha.
4. Escribe las seis cifras y envía. Motocom empareja y arranca ADB sin que copies ningún puerto. Cuando el aviso diga **ADB listo**, vuelve a Motocom; ya puedes saltarte el apartado 3.

Si al bajar la barra se cierra el diálogo del código (pasa en algunos móviles), abre otro: el aviso se pone al día solo. Si no funciona, usa el método manual de abajo.

### A mano: cómo poner Motocom y Ajustes en pantalla dividida

**Pantalla dividida** significa ver dos aplicaciones a la vez: una arriba y otra abajo. Así puedes leer el código en Ajustes y escribirlo en Motocom sin cerrar el diálogo.

**En Xiaomi, Redmi o POCO (MIUI / HyperOS), prueba estos pasos:**

1. Abre **Motocom**. Después abre **Ajustes → Opciones de desarrollador → Depuración inalámbrica**. Todavía no abras el diálogo del código.
2. Abre **Aplicaciones recientes**, la vista donde aparecen las aplicaciones abiertas como tarjetas:
   - Si usas **gestos**, desliza desde el borde inferior hacia el centro y mantén el dedo un instante antes de soltar. Un deslizamiento rápido suele llevarte a Inicio.
   - Si tienes **tres botones abajo**, pulsa el de Recientes (normalmente un cuadrado o tres rayas).
3. Busca la tarjeta de **Motocom** y **mantén el dedo sobre ella** hasta que aparezca un menú.
4. Toca **Pantalla dividida**, o el icono que representa **dos rectángulos, uno encima del otro**. Según la versión, puede aparecer al tocar el icono de la app sobre su tarjeta.
5. El teléfono te pedirá la segunda aplicación: toca **Ajustes**. Deberías ver Motocom en una mitad y Ajustes en la otra; el orden no importa.
6. En la mitad de Ajustes, entra en **Depuración inalámbrica → Vincular dispositivo con código**. Ahora puedes introducir en la otra mitad el puerto y el código, y pulsar **Emparejar**.

**En otros Android**, abre Recientes, toca el **icono de Motocom encima de su tarjeta**, elige **Pantalla dividida**, **Dividir pantalla** o **Abrir en vista de pantalla dividida**, y selecciona Ajustes como segunda aplicación. El nombre y la ubicación cambian según el teléfono.

**Si falta espacio**, arrastra la barra que separa ambas mitades para dar más sitio a Motocom y desplázate dentro de la app hasta los campos. Mantén abierto el diálogo del código de Ajustes mientras emparejas.

**Para salir**, después de emparejar, arrastra la barra central hasta el borde superior o inferior. Una de las apps volverá a ocupar toda la pantalla.

**Si no aparece la opción**, comprueba que estás en Recientes, no manteniendo pulsado el icono del escritorio. Algunos modelos o aplicaciones no admiten pantalla dividida. Si el móvil dispone de **ventana flotante**, puedes abrir Motocom así sobre Ajustes para mantener ambas visibles; esa opción también depende del fabricante.

Referencias de los fabricantes: [pantalla dividida en Xiaomi](https://www.mi.com/co/support/faq/details/KA-753729/) y [pantalla dividida en Google Pixel](https://support.google.com/pixelphone/answer/7444033?hl=es-419).

### Introducir el código

1. Abre Motocom en su pantalla **ADB** (el indicador de arriba a la derecha) y Ajustes en **pantalla dividida**. Es importante mantener abierto el diálogo de emparejamiento de Android; si lo cierras, el código y el puerto pueden caducar.
2. En Depuración inalámbrica, pulsa **Vincular dispositivo con código de vinculación**.
3. Android muestra una dirección como `192.168.1.20:37123` y un código de seis cifras. En el paso **1 · Emparejar** de Motocom, introduce **37123** en **Puerto de emparejamiento** y las seis cifras en **Código de 6 cifras**. Los números son ejemplos: usa los de tu móvil.
4. Pulsa **Emparejar** y espera a que el panel de arriba diga **Emparejado**.

Motocom se conecta a `127.0.0.1`, es decir, al propio móvil. No hay que introducir la IP ni conectarse a otro dispositivo. La identidad se guarda en el almacenamiento privado de la app y no entra en copias de seguridad. El código de emparejamiento no se guarda.

## 3. Iniciar ADB

1. Cierra el diálogo del código y vuelve a la pantalla principal de **Depuración inalámbrica**.
2. Mira **Dirección IP y puerto**. El número después de `:` es el **puerto de conexión**, normalmente diferente al de emparejamiento.
3. Introdúcelo en el paso **2 · Iniciar**, en **Puerto de conexión**, y pulsa **Iniciar ADB**.
4. Espera a que aparezca **ADB activo**. Pulsa **Volver a la moto**.

A partir de ahí el proceso de pantalla se comunica con la app mediante Binder, sin mantener una sesión ADB de red. Puedes desactivar la depuración inalámbrica después del arranque. El cambio de wifi no requiere repetir el emparejamiento. El proceso sigue vivo aunque Android cierre Motocom: al volver a abrirla se engancha solo, sin wifi. Solo hay que iniciarlo otra vez tras reiniciar el móvil o reinstalar la app. Solo atiende órdenes de Motocom.

## 4. Conectar la moto

1. Cierra completamente Carbit Ride y OffNavigator: usan los mismos puertos que Motocom.
2. Enciende la pantalla de la moto y abre su función de conexión al teléfono.
3. Activa el **wifi** en el móvil (y la **ubicación** si tu Android es el 12 o anterior).
4. En Motocom, pulsa **Leer QR de la moto** y apunta al código de su pantalla (la primera vez pide permiso para la cámara). Solo hace falta la primera vez; después la moto aparece en la fila **Moto**. Leer el QR no conecta todavía.
5. En la fila **App**, elige la app que quieres mostrar.
6. En **Tamaño en la moto**, elige el tamaño de letras y botones. La miniatura muestra cuánto mapa cabe. **Elígelo antes de conectar: una vez conectado no se puede cambiar.**
7. Pulsa **Conectar** y acepta los permisos solicitados: **Dispositivos cercanos** (o **Ubicación exacta** en Android 12 o anterior) es imprescindible para encontrar la moto; las **notificaciones** son opcionales. Si deniegas el imprescindible, Motocom lo avisa y te lleva a Ajustes. La app busca por wifi directo la moto que leyó en el QR.
8. Cuando llegue la imagen, el panel de arriba pone **En la moto**. Para escribir un destino, pulsa **Al móvil** (el panel pasa a **La app está en el móvil**) y, al terminar, **A la moto**. Si la moto se reconecta mientras escribes, la app no se te quita del móvil. Comprueba parado qué sucede al bloquear el teléfono antes de usarlo en ruta.

La conexión de vídeo con la moto no necesita internet. Los mapas o servicios de navegación pueden necesitar datos móviles o mapas descargados. Si Motocom avisa del ahorro de batería, pulsa **Permitir**: ayuda a mantener la imagen con el móvil bloqueado.

## Las siguientes veces

- Basta con pulsar **Conectar**: si ADB está parado, Motocom lo inicia sola (si Android le deja encender la depuración inalámbrica; si no, actívala tú antes). En Xiaomi, Redmi o POCO, para que pueda encenderla sola activa en Opciones de desarrollador **Depuración USB (ajustes de seguridad)** antes del primer **Iniciar ADB**. Si ADB no arranca en 3 minutos, Motocom deja de esperar.
- Si Android cierra Motocom o fuerzas su cierre, el proceso ADB sigue vivo: al abrirla vuelve **ADB activo** en uno o dos segundos.
- Tras reiniciar el teléfono o reinstalar la app: activa la depuración inalámbrica y repite **Iniciar ADB**. Fuera de casa, sin wifi, mira el apartado siguiente.
- No suele hacer falta repetir **Emparejar**. Hazlo si Android ha olvidado la identidad, has borrado los datos de Motocom o reinstalado la app. Android puede revocar autorizaciones inactivas.
- **Desconectar** detiene el vídeo, libera la pantalla virtual y finaliza la búsqueda de la moto. El proceso ADB permanece disponible hasta reiniciar el móvil o actualizar la app.
- Si la pantalla de la moto corta la conversación sin apagar su wifi, Motocom vuelve a llamarla sola.

## Fuera de casa, sin wifi

Si ya ponía **ADB activo**, no hace falta nada: ADB sigue en marcha hasta reiniciar el móvil, aunque Android cierre Motocom. Si el móvil se ha reiniciado o apagado, usa el wifi de la moto (probado el 09-10-2026 con el POCO F5):

1. Enciende la moto y abre la conexión con el móvil en su pantalla.
2. Lee el QR de la pantalla de la moto con Motocom (no necesita ADB). Toca la fila **Moto** de la pantalla principal: en **Wifi** verás cómo se llama la red de la moto y en **Contraseña**, su contraseña.
3. En **Ajustes → Wifi**, conéctate a esa red con esa contraseña, como a cualquier wifi. Si avisa de que no hay internet, quédate conectado igualmente.
4. En Motocom, en la pantalla **ADB**, pulsa **Iniciar ADB** y espera a **ADB activo**. Si Android pregunta si permites la depuración en esa red, acepta.
5. **Importante:** vuelve a **Ajustes → Wifi**, toca esa red y pulsa **Olvidar**. Si no la olvidas, la pantalla de la moto no se conecta.
6. Vuelve a Motocom y pulsa **Conectar**.

## Si falla

| Síntoma | Qué comprobar |
|---|---|
| No empareja | Mantén visible el diálogo de Android, usa su puerto y el código nuevo de seis cifras. |
| Empareja pero no conecta | Usa el puerto de la pantalla principal, no el del código. Verifica que la depuración inalámbrica siga activa. |
| Conexión rechazada | El puerto cambia al desactivar depuración, cambiar de red o reiniciar. Vuelve a copiar el puerto actual. |
| ADB conecta pero no arranca la pantalla | Mantén Motocom abierta y reintenta. Puede haber una restricción del fabricante; comparte el registro. Para diagnóstico por ordenador: `adb logcat -d -s MotocomADB`. |
| No encuentra la moto | Wifi encendido (y ubicación en Android 12 o anterior), permiso de dispositivos cercanos concedido y función de conexión abierta en la TFT. |
| La moto no recibe vídeo | Cierra Carbit Ride y OffNavigator y vuelve a conectar. Si el panel dice **La app no ha llegado a la moto**, pulsa **A la moto**. Comparte el registro de Motocom. |
| Se para al bloquear | Revisa el ahorro de batería, la versión de Android y las restricciones de la app elegida. |

Para guardar los detalles del intento, toca el icono **Registro** de arriba a la derecha y después **Compartir**. Si un emparejamiento queda sin respuesta, cierra completamente Motocom y reintenta con un diálogo de código nuevo.

## Android 10: activación por ordenador

Android 10 no ofrece emparejamiento inalámbrico con código. Conecta el móvil al ordenador por USB, activa **Depuración USB** y acepta su autorización. Con las herramientas oficiales Android Platform Tools:

```sh
adb devices
adb tcpip 5555
```

En Motocom introduce **5555** como puerto de conexión, pulsa Iniciar ADB y acepta la autorización de la nueva clave en el móvil si aparece; reintenta si la autorización tarda más que la conexión. No uses los campos de emparejamiento. Tras ver **ADB activo** puedes desconectar el cable. Esta activación se pierde al reiniciar. Para cerrar el puerto TCP posteriormente, conecta por USB y ejecuta `adb usb`.

## Implementación y alcance de las comprobaciones

- `ShellLink`: emparejamiento ADB local, arranque de `ShellMain` mediante `app_process` y seguimiento del proceso.
- `ShellProvider`: recibe el Binder solo de shell/root y con el identificador de la sesión actual.
- `DisplayService`: crea la pantalla virtual con permisos shell; conserva la transferencia de `Surface` por Binder.
- `MirrorPicture`, `BikeLink`, `BikeWifi`: codificación H.264 y conexión a la moto.
- Se eliminan las bibliotecas, el proveedor y los botones de Shizuku.

Comprobado en el teléfono conectado (Android 15 / API 35): tres pruebas instrumentadas correctas. El proceso lanzado con permisos ADB publica su Binder, crea una pantalla virtual real de 592 × 816 con una superficie del codificador, la libera y termina. También se comprueba la recarga de la identidad ADB y el rechazo de llamadas de una app sin privilegios.

La prueba arranca el proceso mediante la instrumentación del teléfono, con permisos shell. Queda pendiente verificar el emparejamiento por código desde la interfaz y la conexión completa con la moto: vídeo, cambio de wifi y bloqueo de pantalla. No se ha probado la compatibilidad en otros modelos o versiones de Android.

Referencias: [biblioteca LibADB Android](https://github.com/MuntashirAkon/libadb-android) y [documentación oficial de ADB](https://developer.android.com/tools/adb).

## Compilar en este PC de 4 GB

Usa siempre el lanzador con límite de memoria, una tarea cada vez:

```sh
scripts/gradle-low-memory.sh :app:assembleDebug
scripts/gradle-low-memory.sh :app:assembleDebugAndroidTest
scripts/gradle-low-memory.sh :app:lintDebug
```

El lanzador usa un grupo de control de systemd: máximo conjunto de **1100 MiB de RAM y 256 MiB de swap**, incluyendo procesos hijos. Impide dos compilaciones simultáneas. Gradle usa un worker y Kotlin se ejecuta en ese mismo proceso. No se inicia un emulador; las pruebas instrumentadas se ejecutan en un teléfono conectado.

El límite es para las herramientas de compilación, no para todo el PC. Si se alcanza, hay que diagnosticar el fallo conservando el límite, en vez de lanzar más compilaciones o dejar memoria ilimitada.
