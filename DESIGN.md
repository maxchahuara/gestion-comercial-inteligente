# Gestión Comercial Inteligente | {ℂℎ}

Interfaz Android nativa en modo Operate. El propietario administra campañas, conversa con un agente y revisa las acciones desde el teléfono. La prioridad es reconocer contexto, autorización y resultado; no se muestran métricas comerciales inventadas.

## Arquitectura

Cuatro destinos: Campañas, Agente, Actividad y Ajustes. Desde 0.2.2 Agente es la entrada inicial y el destino de retorno con Atrás. Campañas es un índice compacto y abre el resumen de una campaña. Crear campaña inicia una conversación sin persistir una ficha vacía hasta el primer mensaje; Crear con formulario es una alternativa secundaria. Abrir o editar una campaña existente no cambia la selección operativa. Crear una nueva o elegirla explícitamente sí establece el contexto. Agente y Actividad comparten un selector visible; «Seleccionada» no significa ejecución automática. Un contexto nuevo pendiente y sus borradores se conservan al cambiar de selección. El fallback consulta la persistencia antes de sustituir un id que aún no llegó al Flow de Room.

El resumen reúne objetivo, contexto, reglas, recursos y copias. Crear/editar usa Contexto, Canales y Reglas sin obligar a completar un wizard. La tarea separa Contenido y Cuándo; la fecha es prevista y no garantiza ejecución exacta. Actividad reúne Tareas, Registro e Interesados. Ajustes tiene Cuenta de ChatGPT, Permisos y control, Aplicaciones y Ayuda y diagnóstico.

La conversación es la vía principal para crear y administrar. Compositor visible sin campaña previa, una pregunta necesaria por turno y adjunto directo después del primer mensaje. El modo permanece visible y las acciones secundarias pasan al menú. Los datos de negocio admiten edición parcial sin vaciar los restantes. Reglas propuestas es una tarjeta inline sin efecto; su revisión nativa presenta destinos, cuenta, límite y silencio completos, con cuerpo desplazable y confirmación humana. Una propuesta desactualizada no sobrescribe reglas nuevas; aprobar no reanuda ni ejecuta borradores anteriores. Los formularios permanecen como configuración avanzada.

## Jerarquía y componentes

Top app bar contextual con estado real y Pausar/Reanudar. No se repite una segunda cabecera de marca. Los datos completos se leen en el detalle; las listas usan resúmenes con salida al contenido íntegro. Las acciones principales se distinguen de configuración, copia y desconexión.

Tipografía Roboto nativa, sin fuentes remotas: cuerpo 16/24sp, texto secundario 14/20sp, etiquetas 14/20sp, notas 12/16sp, títulos 20/26sp y 24/30sp. Se conserva el escalado del sistema. Margen 20dp, escala de 4dp y grupos de 12–20dp. Botones de al menos 48dp y filas de selección completas. Radios 8/12/16/24dp, bordes 1dp y foco 2dp. Iconos Material de una sola familia; sin emojis como controles. La navegación conserva patrones Android, Back y teclado.

Las barras superiores adaptan su altura a la tipografía. En el chat, el bloque de contexto conserva su orden y altura natural; si supera el 55% del espacio disponible, se desplaza dentro de ese límite para mantener accesibles la conversación y el compositor, también con teclado o letra ampliada. No se ocultan acciones por criterios estéticos.

## Color

La fuente de verdad es [la guía Cobalto sereno suministrada por el propietario](docs/diseno/COBALTO_SERENO.md). Fondo claro #F8FAFC, superficie #FFFFFF, texto #0F172A y secundario #475569. Primario exacto #1D4ED8 y presionado #1E40AF; selección #EFF6FF e información #EAF2FF. Bordes #E2E8F0; campos con contorno #64748B para conservar legibilidad. El turquesa se limita a acentos del agente y adjuntos; para texto se utiliza la variante accesible #0F766E, sin blanco sobre #14B8A6.

Avisos pendientes e incertidumbre usan #B45309 sobre #FFFBEB; errores reales #B91C1C sobre #FEF2F2; completado #15803D sobre #F0FDF4. Borrador, pausa y espera son neutros. Presentada a la app es informativa y nunca se colorea como entrega confirmada. Se conservan los nombres, iconos y explicaciones de los estados.

El modo oscuro existente sigue la preferencia de Android: fondo #0F172A, superficie #17243A, texto #F8FAFC y primario #ADC6FF. Sus colores semánticos tienen equivalentes claros sobre fondos oscuros. No se usan colores dinámicos del fondo de pantalla. Todos los niveles surfaceContainer e inverseSurface están definidos para menús, diálogos y mensajes transitorios. Theme.kt centraliza colores, tipografía, formas y tokens; StyledControls.kt aplica el estilo a controles nativos sin sustituir sus callbacks ni semántica.

## Estados y recuperación

Desde 0.2.5 una campaña de atención con un post previo distingue Preparando guion y enlace de Atento a mensajes entrantes. El agente recibe capturas automáticamente y decide su ruta; el humano no conduce las redes. Verificar enlace y guion es una tarea de lectura, con estado Verificada al recibir la URL y aprobar el ensayo local. El detalle muestra el autor y la descripción, evitando exponer su instrucción JSON como flujo de producto. El receptor de compartir presenta solo un aviso transitorio de recepción; no abre un formulario ni permite envíos. La pausa conserva su prioridad sobre cualquier preparación automática.

Desde 0.2.4 Solo responder mensajes entrantes es un modo separado de Automático. La revisión distingue Puede responder a de Puede escribir a y declara Iniciar conversaciones: No permitido. El comodín se explica como Cualquier persona que escriba directamente y solo pertenece a ese modo. El encabezado muestra Atento a mensajes entrantes únicamente con el listener conectado y la pausa retirada; durante la generación sigue mostrando Preparando respuesta. La respuesta por notificación no se presenta como control visual de pantalla.

Desde 0.2.3 la conversación también permite continuar una tarea pendiente sin crear otra, únicamente si no existe un intento de envío. Un cartel se compone en Android con título, eslogan, número y pie, queda como recurso de la campaña y puede revisarse antes de adjuntarlo. En Facebook, volver del último paso al compositor conserva el borrador para verificarlo; la app nunca presenta como entregado un resultado que solo fue pulsado.

Los éxitos transitorios usan snackbar; los bloqueos permanecen en la tarea o pantalla correspondiente, con una acción útil. Preparando respuesta y Operando una aplicación son estados distintos. Borradores no autoriza envíos. Resultado incierto conserva la evidencia y no ofrece reintento ni cancelación como si deshiciera el efecto.

Los formularios validan junto al campo y llevan a la sección del primer error. Solo preguntan por descarte si hay cambios. No cierran antes de persistir. El chat conserva el texto por campaña y permite recuperar un intento fallido para editar, sin repetir automáticamente acciones.

## Seguridad de interacción

Los permisos siguen siendo decisiones humanas. La cuenta configurada no demuestra conexión vigente; una app instalada no demuestra sesión o compatibilidad. Cambiar o desconectar cuenta pausa el agente y conserva la verificación de identidad existente. Los detalles técnicos son secundarios, seleccionables y no ejecutan HTML.

Copias usa el selector Guardar de Android y contraseñas en memoria. Restaurar crea una campaña nueva en borradores y conserva las actuales. El laboratorio mantiene los ids sandbox_* y distingue su prueba automática local de las decisiones de ChatGPT.

Desde 0.2.1 el control visual tiene un halo azul estático de 48 dp con un degradado de opaco en el borde a transparente hacia el interior. Canvas lo dibuja en una ventana de Accesibilidad sin toques. El aviso azul oscuro es compacto y queda junto al borde: «El agente tiene el control» como contexto y «Recuperar control» como acción destacada. Mantiene al menos 48 dp de toque, adapta el alto al texto y no toma el foco del teclado. Se aparta del objetivo cuando hace falta. En Android 14+ se captura la ventana de la app sin estas capas para la visión del agente. El botón pausa globalmente y revoca la sesión visual antes de retirar ambas ventanas; la reanudación es humana desde la app. Finalizar, interrumpir o desconectar el servicio retira el indicador. Planificar o responder sin operar la pantalla no muestra el halo. Los mockups de la auditoría anterior documentan 0.2.0, antes de esta función.

## Evidencia

Los archivos de auditoría local se mantienen fuera de la distribución pública. Los componentes nativos y la especificación prevalecen sobre artefactos del generador. Las capturas reales y datos de QA se mantienen bajo app/build, excluidos de Git. La variante visualQa usa un paquete y datos separados de Campaña para revisar la apariencia sin tocar campañas, cuentas ni permisos reales.
