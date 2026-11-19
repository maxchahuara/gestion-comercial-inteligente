# Gestión Comercial Inteligente | {ℂℎ}

Agente Android de código abierto para crear y administrar campañas conversando desde el celular. La app ejecuta sus herramientas, cola y acciones en Android. Usa internet para consultar ChatGPT y las redes sociales. El uso normal funciona desde el teléfono.

**Primera versión pública experimental · v0.4.3**

[Descargar el APK firmado](https://github.com/maxchahuara/gestion-comercial-inteligente/releases/latest/download/gestion-comercial-inteligente-android.apk) · [Notas de la versión](https://github.com/maxchahuara/gestion-comercial-inteligente/releases/tag/v0.4.3)

## Empezar en Android

1. Desde un teléfono con Android 11 o superior, descarga e instala el APK. Android puede pedir autorización para instalarlo desde el navegador o gestor de archivos.
2. Abre la app, conecta tu cuenta de ChatGPT y elige uno de los modelos disponibles.
3. En **Agente**, cuéntale qué ofreces y qué quieres conseguir. La primera campaña se crea en borradores.
4. Si quieres operar redes, inicia sesión primero en sus aplicaciones. En GCI, activa los permisos que correspondan, revisa las reglas propuestas y pulsa **Reanudar**.

Desde **Recuperar control** puedes detener la sesión. La aprobación del propietario se realiza en la app.

## Qué incluye

- Gestión de campañas, contenido, recursos, tareas e interesados por chat.
- Atención a mensajes directos de WhatsApp con el número registrado en el teléfono. Messenger comprueba la fanpage configurada. Facebook e Instagram tienen flujos visuales experimentales, sujetos a la interfaz disponible.
- Respuestas enfocadas en la campaña. El modo de solo atención entrante responde a mensajes que llegan directamente.
- Transcripción local en español con Vosk cuando la red permite obtener la nota de voz.
- Generación de imágenes con Codex en **Android ARM64**, con autorización independiente en **Ajustes → Cuenta de ChatGPT**.
- [Conexión Gatuna](docs/ejemplo-gatuno/CONEXION_GATUNA.md), ejemplo ficticio con doce opciones, descripciones e ilustraciones. Las publicaciones temáticas empiezan en borradores.

## Compatibilidad y datos

Los cambios de interfaz, el estado de la red, las notificaciones, el bloqueo y la gestión de batería de Android afectan la operación. Una tarea presentada a una red aún puede requerir comprobar que se entregó o publicó. El alcance del acceso a modelos depende de la cuenta conectada.

Las campañas y archivos se guardan en el teléfono. Al usar funciones de IA, la app puede enviar a OpenAI el contexto necesario para la tarea, incluidos mensajes, transcripciones, imágenes o capturas de la ventana autorizada. Vosk procesa el audio en el dispositivo. La app guarda las credenciales de ChatGPT en almacenamiento protegido por Android Keystore.

## Código y licencia

Creado por **Max Chahuara**. El código original se distribuye con [licencia MIT](LICENSE). Puedes usarlo, modificarlo y redistribuirlo conservando el aviso de copyright y el texto de la licencia. Los componentes externos y el código adaptado conservan sus [avisos y licencias](THIRD_PARTY_NOTICES.md).

Para compilar, usa JDK 17 y Android SDK 36. La [guía de desarrollo](docs/DESARROLLO_V1.md) cubre Gradle, pruebas y firma. El [diseño Cobalto sereno](DESIGN.md) documenta los componentes visuales.
