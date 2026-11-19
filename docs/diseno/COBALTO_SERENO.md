# Campaña — Guía de estilo visual «Cobalto sereno»

**Tipo de documento:** especificación de diseño visual para rediseñar una aplicación Android existente.\
**Producto:** Campaña — asistente conversacional para crear y administrar campañas de marketing.\
**Alcance:** apariencia visual y consistencia de la interfaz; **no** arquitectura de pantallas, navegación, lógica ni funcionalidades.\
**Idioma de la interfaz:** español.\
**Principio rector:** *potencia tecnológica sin complejidad visual*.

> **Instrucción principal para el agente de desarrollo:** adapta el aspecto de la aplicación existente a este sistema visual. Conserva sus pantallas, controles, textos funcionales, permisos, validaciones, flujos, estados, autorizaciones y comportamientos. Este archivo **no** indica dónde debe colocarse cada elemento ni solicita inventar pantallas o funciones.

---

## 1. Identidad y personalidad visual

La interfaz debe sentirse **confiable, clara, actual, tranquila y humana**, como un asistente profesional que explica acciones complejas con sencillez. Debe transmitir control por parte del usuario, especialmente cuando el agente propone o realiza acciones sobre otras aplicaciones.

- **Estilo:** minimalismo funcional, contemporáneo, limpio, ligero y apto para uso frecuente.
- **Inspiración técnica:** claridad y consistencia de Material Design 3, adaptadas a una identidad propia.
- **Tono visual:** profesional y accesible; nunca infantil, excesivamente corporativo ni recargado.
- **Densidad:** moderada, con espacios de respiración y agrupaciones claras.
- **Sensación conversacional:** amable, directa y natural, sin parecer una app de mensajería genérica.
- **Jerarquía:** destacar acciones primarias y estados críticos; reducir el protagonismo de lo secundario.
- **Evitar:** fondos saturados, tarjetas de colores indiscriminados, degradados dominantes, brillos intensos, neomorfismo, efectos 3D, sombras pesadas y decoración que compita con el contenido.

## 2. Paleta oficial — «Cobalto sereno»

Esta es la fuente única de verdad para los colores de la marca y los fundamentos de la interfaz. Los nombres de tokens son semánticos: **no deben sustituirse por colores elegidos al azar**.

### 2.1. Colores principales

| Token | HEX | Uso visual general |
|---|---|---|
| `brand.primary` | `#1D4ED8` | Identidad cobalto, elementos interactivos principales y énfasis. |
| `brand.primaryPressed` | `#1E40AF` | Estado presionado y variaciones más profundas del cobalto. |
| `brand.accent` | `#14B8A6` | Acento turquesa reservado para detalles y señales visuales puntuales. |
| `brand.accentAccessible` | `#0F766E` | Variante oscura de acento para texto/iconos legibles sobre claro. |
| `surface.canvas` | `#F8FAFC` | Fondo general claro, ligeramente frío. |
| `surface.default` | `#FFFFFF` | Superficies principales, paneles, tarjetas y elementos elevados. |
| `surface.aiSoft` | `#EAF2FF` | Superficie de énfasis suave asociada al asistente y a información positiva/neutra. |
| `text.primary` | `#0F172A` | Títulos y contenido principal. |
| `text.secondary` | `#475569` | Descripciones, ayuda, etiquetas secundarias. |

**Proporción orientativa, no matemática:** predominarán blancos y grises muy claros; el cobalto señalará las acciones y selecciones más importantes; el turquesa aparecerá con moderación. La interfaz **no** debe estar constantemente teñida de azul.

### 2.2. Neutros y límites

| Token | HEX | Uso visual general |
|---|---|---|
| `text.muted` | `#64748B` | Texto auxiliar solo cuando el tamaño y contraste sean adecuados. |
| `border.default` | `#E2E8F0` | Bordes y divisiones sutiles. |
| `border.strong` | `#CBD5E1` | Separaciones más visibles y contornos inactivos. |
| `surface.subtle` | `#F1F5F9` | Superficie alternativa de bajo énfasis. |
| `surface.selected` | `#EFF6FF` | Selección ligera sin utilizar superficies de color intenso. |

### 2.3. Colores semánticos y estados

| Token | HEX | Significado |
|---|---|---|
| `state.success` | `#15803D` | Confirmado, completado o correcto **solo si realmente lo está**. |
| `state.successBg` | `#F0FDF4` | Fondo suave para éxito. |
| `state.warning` | `#B45309` | Requiere revisión, atención o resultado incierto. |
| `state.warningBg` | `#FFFBEB` | Fondo suave para advertencia. |
| `state.error` | `#B91C1C` | Error real o fallo confirmado. |
| `state.errorBg` | `#FEF2F2` | Fondo suave para error. |
| `state.info` | `#1D4ED8` | Proceso, información o actividad no confirmada como éxito. |
| `state.infoBg` | `#EAF2FF` | Fondo informativo. |
| `state.neutral` | `#475569` | En espera, pausado, borrador o sin actividad. |

**Regla crítica:** no representar “presentada a la aplicación” como “entregada” o “publicada”. Tampoco presentar una autorización propuesta como una autorización aplicada. Las distinciones funcionales deben mantenerse en texto, iconos y color.

### 2.4. Combinaciones accesibles

- Sobre `brand.primary` (`#1D4ED8`), usar texto/iconos **blancos** (`#FFFFFF`). Contraste aproximado: **6,7:1**.
- Sobre `brand.accent` (`#14B8A6`), **no** usar texto blanco normal: el contraste aproximado es **2,5:1**. Preferir `text.primary` (`#0F172A`) o utilizar `brand.accentAccessible` para texto sobre blanco.
- `text.primary`, `text.secondary` y `text.muted` son adecuados sobre fondo blanco para texto normal en sus tamaños previstos; **revalidar** si se cambia el fondo o la opacidad.
- Nunca comunicar un estado únicamente mediante color; añadir nombre de estado y/o icono.
- Objetivo mínimo: **WCAG AA**, contraste de texto normal **≥ 4,5:1**, texto grande **≥ 3:1** y elementos gráficos funcionales **≥ 3:1** frente a sus colores adyacentes.

## 3. Tipografía

**Familia principal recomendada:** **Inter**, por su legibilidad, neutralidad y estética moderna.\
**Alternativa nativa:** **Roboto** si Inter no está integrada en el proyecto o resulta necesario mantener una implementación Android sin nuevas fuentes.\
**Restricción:** usar **una sola familia principal** de manera consistente. No mezclar fuentes decorativas ni usar fuentes distintas para simular “inteligencia artificial”.

### 3.1. Pesos

- `Regular / 400`: texto corriente y contenido conversacional.
- `Medium / 500`: controles, etiquetas y subtítulos breves.
- `SemiBold / 600`: títulos de sección y énfasis moderado.
- `Bold / 700`: títulos importantes, con moderación.

### 3.2. Escala tipográfica sugerida

| Token | Tamaño | Interlineado | Peso sugerido |
|---|---:|---:|---|
| `type.display` | `28sp` | `34sp` | 700 |
| `type.titleLarge` | `24sp` | `30sp` | 600–700 |
| `type.title` | `20sp` | `26sp` | 600 |
| `type.subtitle` | `16sp` | `24sp` | 600 |
| `type.body` | `16sp` | `24sp` | 400 |
| `type.bodySmall` | `14sp` | `20sp` | 400 |
| `type.label` | `14sp` | `20sp` | 500–600 |
| `type.caption` | `12sp` | `16sp` | 500 |

Estas medidas son **tokens de estilo, no instrucciones de posición**. Los tamaños pueden adaptarse al escalado tipográfico y a componentes existentes; no fijar alturas que corten el texto.

**Reglas:**

- Priorizar lectura cómoda y jerarquías claras frente a títulos grandes por motivos decorativos.
- Evitar bloques largos en negrita, mayúsculas sostenidas y espaciados entre letras excesivos.
- No usar texto por debajo de `12sp` como solución para hacer caber contenido.
- Respetar el escalado de fuente del sistema Android.
- Usar números tabulares solo donde comparaciones y conteos lo requieran.
- Mantener lenguaje de interfaz claro, español natural y nombres funcionales existentes.

## 4. Formas, bordes y redondeos

La identidad usa geometría **suave y funcional**, con esquinas redondeadas y contornos discretos.

| Token | Valor | Aplicación genérica |
|---|---:|---|
| `radius.small` | `8dp` | Controles compactos y pequeños elementos. |
| `radius.medium` | `12dp` | Campos y superficies de tamaño medio. |
| `radius.large` | `16dp` | Contenedores, tarjetas y botones de mayor presencia. |
| `radius.extraLarge` | `24dp` | Superficies amplias y paneles flotantes compatibles. |
| `radius.pill` | `999dp` | Chips y etiquetas ovaladas, solo cuando aporte claridad. |
| `stroke.default` | `1dp` | Contornos discretos. |
| `stroke.focused` | `2dp` | Enfoque/selección cuando sea necesario identificarla. |

- Evitar mezclar arbitrariamente bordes muy rectos con otros exageradamente redondeados.
- Los límites entre superficies deben reconocerse principalmente por **contraste sutil y espacio**; usar elevación solo cuando haga falta.
- Evitar bordes gruesos, dobles contornos decorativos y elementos con aspecto de juguete.

## 5. Espaciado y ritmo visual

Usar una **escala base de 4dp** para construir una cadencia consistente: `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48dp`.

- Mantener una percepción de amplitud sin introducir espacios vacíos arbitrarios.
- Agrupar visualmente elementos relacionados con distancias coherentes.
- Reservar las separaciones mayores para cambios significativos de contexto.
- Mantener la altura táctil mínima recomendada de **48dp** en elementos interactivos, aunque el icono visible sea menor.
- Respetar áreas seguras del sistema, escalado de texto y teclado.

**Importante:** esta escala **no** impone márgenes específicos, coordenadas, jerarquía de contenedores ni cambios en la distribución actual.

## 6. Iconografía e ilustraciones

- Usar un único lenguaje de iconos, preferentemente **Material Symbols Rounded** o el set ya existente si es consistente.
- Trazos simples, geometría legible y grosor óptico uniforme; tamaño habitual **20–24dp** para iconos funcionales.
- Priorizar iconos conocidos y acompañarlos con texto cuando su significado pueda resultar ambiguo.
- Tratar el turquesa como pequeño acento, no como color universal de todos los iconos.
- Evitar iconos 3D, emojis como controles principales, múltiples estilos mezclados y robots/mascotas excesivamente protagonistas.
- Las ilustraciones, si ya existen o son necesarias, deben ser planas, ligeras y secundarias al contenido real.

## 7. Superficies, profundidad y efectos

- Preferir superficies claras, nítidas y sin texturas.
- Las tarjetas pueden diferenciarse por borde sutil, color de superficie o elevación leve; no aplicar los tres con fuerza al mismo tiempo.
- Utilizar sombras de intensidad baja, bordes suaves y un sistema de elevación limitado.
- **Degradados:** opcionales y muy discretos; **nunca** como fondo dominante de toda la interfaz ni sustituto del contraste.
- Evitar efectos de vidrio, halos permanentes, brillo, neón y animación ornamental que reste legibilidad.
- El indicador funcional de control del agente sobre otras apps es un caso especial: **no modificar su significado ni su comportamiento** durante un rediseño cosmético.

## 8. Lenguaje visual de componentes

Estas pautas definen **apariencia y consistencia**, no ubicación ni cantidad de componentes.

- **Acciones primarias:** cobalto sólido y etiqueta blanca; una jerarquía visual inequívoca.
- **Acciones secundarias:** superficie blanca o azul muy suave, con texto cobalto y contorno discreto si hace falta.
- **Acciones terciarias:** poco peso visual y texto claro; no competir con la principal.
- **Acciones destructivas o críticas:** usar estilos semánticos apropiados y conservar las confirmaciones existentes.
- **Campos de texto:** fondo claro, contorno o separación legible, foco visible, cursor y selección de la misma familia cobalto.
- **Chips y selecciones:** cambios de estado reconocibles por borde, fondo, texto e icono; no depender solo del relleno de color.
- **Tarjetas:** superficies de lectura limpia, sin rellenos de color intenso en todos los elementos.
- **Avisos y confirmaciones:** distinguir información, advertencia, error y éxito mediante semántica explícita.
- **Contenido conversacional:** diferenciar con sutileza las intervenciones del usuario y las del agente. Favorecer legibilidad, continuidad y confianza; no imitar una red social.
- **Etiquetas de estado:** breves, legibles y consistentes; no simplificar conceptos que en la app poseen significados distintos.

## 9. Tratamiento visual de estados importantes del producto

El diseño debe **respetar los estados realmente implementados** en Campaña. La app gestiona campañas, borradores, reglas, tareas, atención a mensajes y operaciones sobre apps sociales del teléfono; **no** debe estilizarse como un panel de métricas publicitarias que no existe.

- **Agente en espera / pausado:** apariencia neutra y serena; nunca representarlo visualmente como “operando”.
- **Preparando respuesta / operando una aplicación:** señal informativa cobalto; actividad perceptible sin animación invasiva.
- **Borrador:** identificación neutral que no sugiera publicación ni permiso concedido.
- **Reglas propuestas:** énfasis de *revisión pendiente*; no presentarlas como reglas ya activas.
- **Reglas autorizadas:** estilo de confirmación **solo tras la autorización real**.
- **Tarea programada / en ejecución:** estado claro y diferenciado del resultado final.
- **Necesita revisión / resultado incierto:** advertencia; evitar iconos de éxito, check verde o mensajes que impliquen que se envió con certeza.
- **Presentada a la app:** señal informativa. No equivale a mensaje entregado ni publicación confirmada.
- **Error real:** tratamiento de error legible con explicación existente y acciones permitidas.

Ninguna actualización visual debe omitir las acciones de **Pausar, Reanudar, Revisar, Autorizar, Cancelar, Recuperar control** u otras ya presentes, ni cambiar sus consecuencias.

## 10. Movimiento y retroalimentación

- Transiciones breves y discretas, generalmente **150–250ms** para cambios visuales sencillos.
- Usar movimiento para reforzar causa y efecto, no como decoración.
- Respetar la preferencia del sistema de reducir o desactivar animaciones.
- Ofrecer retroalimentación perceptible ante acciones, estados de carga y fallos existentes.
- No añadir animaciones que hagan parecer completadas acciones aún pendientes.

## 11. Accesibilidad Android

- Mantener contraste **WCAG AA** como referencia mínima.
- Conservar nombres accesibles, roles, descripciones y estados de controles.
- Compatibilidad con escalado de fuente, dispositivos pequeños, teclado y tecnologías de asistencia.
- Área táctil de al menos **48 × 48dp** cuando corresponda.
- Asegurar que los estados de foco, selección, deshabilitado, cargando y error sean identificables.
- No utilizar únicamente color, vibración o movimiento para comunicar información importante.
- Seguir pautas de Material 3 y convenciones Android sin perder el cobalto como identidad de marca.

## 12. Implementación en el proyecto existente

1. **Inventariar el tema visual actual** y centralizar estos colores, radios, tipografías, espaciados y estilos en tokens reutilizables. Respetar la arquitectura existente (por ejemplo, el sistema de temas que ya emplee el proyecto).
2. **Aplicar la nueva apariencia a los componentes reutilizados** y extender la consistencia al resto de la app sin rediseñar su estructura por iniciativa propia.
3. **Conservar toda la lógica funcional:** conversaciones, selección de campañas, manejo de borradores, propuestas y autorizaciones de reglas, tareas, interesados, adjuntos, cuentas de ChatGPT, permisos Android y control de aplicaciones externas.
4. **No introducir funcionalidades no implementadas:** no crear dashboards de alcance/CTR/ROAS, compra de anuncios, bandejas de conversaciones sociales o pantallas nuevas solo porque aparecieran en conceptos visuales anteriores.
5. **No modificar la navegación ni los destinos**: Agente, Campañas, Actividad y Ajustes, junto con las vistas, pestañas y diálogos implementados.
6. **No alterar textos de seguridad o estados críticos** por motivos meramente estéticos; conservar diferencias entre preparar, autorizar, intentar, presentar y verificar.
7. **No depender de servicios externos ni de recursos remotos de tipografía** para pintar la interfaz. Si se incorpora Inter, integrarla de manera local y conforme a su licencia; si no, usar Roboto.
8. Revisar los cambios con dispositivos Android, tamaño de fuente aumentado, modo de alto contraste si corresponde y distintos estados de cada componente.

### Prioridad ante conflictos

1. **Seguridad y comportamiento ya implementado.**
2. **Accesibilidad y legibilidad.**
3. **Consistencia del sistema de diseño.**
4. **Preferencias decorativas.**

## 13. Criterios de aceptación del rediseño

El trabajo visual está listo cuando:

- [ ] El cobalto `#1D4ED8` es el color primario consistente, con turquesa usado solo como acento.
- [ ] Fondos y superficies se mantienen predominantemente claros.
- [ ] Tipografía, jerarquías, radios, bordes, espaciados e iconos obedecen a tokens compartidos.
- [ ] La interfaz es legible y las acciones principales son fáciles de identificar.
- [ ] El asistente conversacional se percibe claro y cercano, sin decoración distractora.
- [ ] Pausa, espera, ejecución, autorización, borrador, advertencia y éxito se distinguen correctamente.
- [ ] El contraste del texto y los estados interactivos se ha verificado.
- [ ] La navegación, estructura, permisos, acciones y lógica de la app siguen funcionando igual.
- [ ] No se han agregado pantallas, métricas, servicios o comportamientos que el proyecto no tiene.

---

## Instrucción breve reutilizable para un agente de código

> Rediseña **solo la capa visual** de la aplicación Android **Campaña** con el sistema de diseño «Cobalto sereno» descrito en este archivo. Usa sus tokens exactos de color, tipografía Inter (o Roboto como alternativa), redondeos suaves, superficies claras, iconografía coherente y jerarquía visual sobria. Reutiliza los componentes y la arquitectura existentes. **No muevas ni agregues controles por decisión propia; no cambies pantallas, navegación, lógica, permisos, estados ni validaciones.** Verifica accesibilidad y conserva las distinciones funcionales entre borradores, propuestas, autorizaciones, ejecución y resultados inciertos.

*Documento de estilo visual, no wireframe ni especificación de distribución de pantallas.*
