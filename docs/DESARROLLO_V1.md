# Desarrollo de Gestión Comercial Inteligente | {ℂℎ}

## Entorno

Se reutilizan Git, OpenJDK 17 y el SDK Android existentes. El proyecto utiliza Gradle Wrapper 8.14.3 con checksum, AGP 8.13.2 y Kotlin 2.2.10. Android Studio es opcional. No depende de archivos de Tinti, Teclaro u otros proyectos.

Configura ANDROID_HOME o un local.properties privado con sdk.dir. Para compilar y comprobar en Windows:

    .\tools\Build.ps1

En una ruta de Windows con caracteres Unicode, `Build.ps1` usa una unidad temporal para que el ejecutor de pruebas de Java 17 pueda leer las clases. La unidad se libera al terminar.

Para generar una identidad de firma local y un APK optimizado:

    .\tools\New-SigningKey.ps1
    .\tools\Build.ps1 -Release

La identidad se conserva fuera del repositorio, en el perfil del desarrollador. CAMPANA_SIGNING_FILE permite usar otro archivo de propiedades con storeFile, storePassword, keyAlias y keyPassword. No publiques ese archivo ni la clave. Quien compile su propia distribución debe conservar su identidad para poder actualizarla.

## Verificaciones

    .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest

Las pruebas unitarias cubren políticas, límites, horarios, identidad JWT y lectura de Responses SSE. Las pruebas instrumentadas comprueban Room, cifrado y restauración de copias, almacén de cuentas y control del laboratorio.

Con un teléfono de pruebas conectado:

    .\gradlew.bat :app:connectedDebugAndroidTest

La prueba del laboratorio requiere Accesibilidad habilitada. Los tests ordinarios usan datos locales de QA. Las pruebas LivePhoneTest y SelfSendPhoneTest se omiten salvo que reciban argumentos explícitos: utilizan la sesión real del teléfono y solo deben ejecutarse en chats propios de prueba autorizados. No se almacenan destinatarios reales en el código.

## Implementación

La versión inicial utiliza un motor Kotlin propio, herramientas tipadas, Room y WorkManager. La conexión SIWC y HTTPS/SSE es propia, con Nimbus para verificar firmas. Esto evita incorporar otro runtime o un framework que no sea necesario para este incremento. Koog, Ktor, AppAuth, OCR Tesseract y video Media3 permanecen como candidatos de ampliación; no se presentan como dependencias ya instaladas en el APK.

Los adaptadores iniciales operan las apps mediante Accesibilidad, notificaciones y FileProvider. Cada ejecución vuelve a comprobar la política. Los mensajes entrantes se procesan sin herramientas administrativas. No existe acceso arbitrario al shell, filesystem o aplicaciones ajenas.

El servicio solicita también las vistas secundarias mediante [FLAG_INCLUDE_NOT_IMPORTANT_VIEWS](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INCLUDE_NOT_IMPORTANT_VIEWS), necesario en el selector probado. Conserva isAccessibilityTool=false y no intenta abrir ventanas protegidas. El código de pruebas que reactiva el servicio autorizado utiliza UiAutomation: su ejecutor separa argumentos directamente, por lo que no deben introducirse comillas de shell dentro de los valores de componentes. Esta utilidad no forma parte del APK del producto.

## Distribución

El APK de desarrollo está en app/build/outputs/apk/debug/app-debug.apk; el optimizado en app/build/outputs/apk/release/app-release.apk cuando existe configuración de firma. Las pruebas, caches, claves y local.properties se excluyen de Git.

runtimeInventory genera app/build/reports/dependencies/runtime.json con las versiones realmente resueltas. Las fuentes y la licencia están preparadas para publicar en un repositorio; crear o publicar ese repositorio es una operación independiente.

Después de verificar la firma y las pruebas, `tools/Package.ps1` prepara el APK y el ZIP de fuentes en `dist/`, junto con el inventario de dependencias y `SHA256SUMS.txt`. El APK puede descargarse e instalarse directamente desde el teléfono.

prepareVoiceModel obtiene el modelo español original de Vosk desde su editor, lo guarda en la caché de Gradle y verifica el SHA-256 antes de empaquetarlo como recurso generado. CAMPANA_VOSK_MODEL_ZIP permite reutilizar un ZIP local que debe superar la misma comprobación. Los pesos no se duplican en las fuentes versionadas. El APK final incluye el modelo y no necesita descargarlo al ejecutarse. La extracción al almacenamiento privado del teléfono vuelve a comprobar la integridad y limita rutas, cantidad de archivos y tamaño. Los datos del modelo se excluyen de copias.

NativeVoiceQaActivity existe solo en DEBUG y exige DUMP. Genera una frase mediante la voz española local del teléfono, transcribe el WAV y una copia codificada en Opus/OGG, sin micrófono ni mensajes externos. -PminifyVoiceQa=true aplica R8 y las mismas reglas de conservación de Vosk/JNA al APK de ensayo para detectar fallos que solo aparecen tras optimizar. El release no incorpora esa actividad ni OpusVoiceFixture.

La comprobación de continuidad se ejecuta desde NativeSessionQaActivity del source set DEBUG. Se inicia exclusivamente la actividad propia del laboratorio y luego el mismo servicio nativo lee, escribe y despacha gestos dentro de esa ventana. La actividad registra el resultado con la etiqueta CampanaLocalQA; no opera redes, no crea notificaciones falsas y conserva la pausa final. Si comienza pausada, comprueba el control local y declara que no ensayó una sesión global activa. El APK release no contiene esta actividad ni su entrada de manifest. Esto evita reiniciar por instrumentation el proceso que aloja Accesibilidad. Para fixtures puros de notificaciones y Room sigue disponible IncomingReplyTest.

ConversationJourneyBridgeTest requiere enableConversationJourney=true y solo simula entradas humanas dentro de Campaña. Sus comandos de escritura y toque rechazan las apps sociales; allí solo permite observación y capturas. Los envíos y publicaciones corresponden al ejecutor del teléfono. tools/journey_qa.py es el controlador de desarrollo de ese puente, no una dependencia del APK. Sus registros y capturas quedan en app/build, fuera de la distribución de fuentes. No ejecutes otra instrumentation mientras haya una tarea real en curso: reinicia el proceso objetivo.
