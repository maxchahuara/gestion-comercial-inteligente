# Conexión Gatuna

El Partido Gatuno del Perú es una campaña ficticia de prueba. En el chat del propietario, el menú de opciones incluye «Vista previa del menú gatuno». En una conversación entrante verificada, escribe HOLA, MENÚ o un número del 1 al 12. Puedes enviar una pregunta libre en español.

Prueba «MESA GATO-08», «CANDIDATO LUNA BIGOTES» y «12». La última opción deja una solicitud de atención humana en Actividad → Registro. Escribe AVISO para guardar el consentimiento de ese chat y BAJA para retirarlo. Cuando el dueño confirme la apertura, el agente incluirá el aviso en una respuesta al siguiente mensaje directo de esa conversación. La campaña conserva su política de atención entrante.

Los campos siguientes se escriben en «Información aprobada» de la campaña, uno por línea. Usa datos ficticios para esta demostración y proporciona enlaces aprobados de la campaña.

```text
Plan Nacional: [texto aprobado]
Plan Lima: [texto aprobado]
Plan Provincias: [texto aprobado]
Candidatos: Michi Cobalto · Nacional; Luna Bigotes · Lima; Copito del Valle · Provincias
Video: [URL de Instagram, YouTube o TikTok]
Manual: [URL en Google Drive, Google Docs, Facebook o Instagram]
Mesa de transeúntes: ABIERTA
Fecha de apertura: [fecha de la demostración]
```

## Publicaciones por tema

La serie incluye doce borradores para la fanpage de la campaña. Cada borrador tiene su propio texto e imagen en Actividad → Tareas y Recursos. Tras verificar el enlace de Facebook, el agente lo añade a la respuesta de esa opción y a sus consultas específicas.

| Opción | Título de la imagen | Escena |
| --- | --- | --- |
| 1 | ¿Miembro de mesa? | un gato negro de pecho blanco examinando un padrón ilustrado junto a una urna pequeña color coral, mobiliario de madera clara, luz matinal |
| 2 | Mesa de transeúntes | una gata tricolor viajera con bolso pequeño junto a una estación andina de tren, mapa plegado sin texto y cielo lavanda |
| 3 | Planes y propuestas | tres gatos alrededor de un plano ilustrado de una red de hogares temporales, con un refugio de madera visible al fondo |
| 4 | Plan Nacional | un mapa de Perú reconocible tejido en fieltro, sobre el que varios gatos de diferentes pelajes conectan casitas con cordones |
| 5 | Plan Lima | un gato carey en una casa de adopción luminosa con referencia a las fachadas y balcones de Lima, voluntariado representado por un segundo gato |
| 6 | Plan Provincias | dos gatos en una red de refugios pequeños entre Andes, costa y selva sugeridos con un paisaje editorial continuo |
| 7 | Busca una candidatura | un gato atigrado de grandes bigotes junto a un archivo colorido con fichas pictóricas y una lupa |
| 8 | Videos y preguntas | un gato siamés curioso junto a una cámara de cine pequeña y una pantalla coral con icono de reproducción |
| 9 | Reglas de la prueba | un gato de pelaje plateado hojeando un cuaderno encuadernado con ilustraciones geométricas y marcas de página |
| 10 | Participación presencial | una gata naranja acercándose a una urna lavanda con una papeleta neutra y una discreta fila de gatos en un recinto luminoso |
| 11 | Manual de la prueba | un manual ilustrado abierto sobre un escritorio, con un gato negro asomando y una huella dibujada en la página |
| 12 | Habla con el equipo | dos gatos con auriculares ligeros en una mesa de ayuda acogedora con una pequeña planta y notas geométricas |

Imágenes creadas con la herramienta integrada ImageGen. Cada solicitud usó el título y la escena de la tabla con esta dirección artística común:

> Use case ads-marketing. One standalone square social post artwork for the fictional Spanish-language test campaign Conexión Gatuna. High-end editorial clay-and-felt diorama photography, tactile fur, soft directional studio light, beautiful rich cobalto blue and warm cream contrasted with coral and lavender accents, smart clean composition. Upper quarter is integrated solid dark cobalt with the exact large white Spanish title provided, professionally typeset, centered. Main diorama fills middle and bottom. Small legible footer 'CONEXIÓN GATUNA · SIMULACIÓN'. No other text, watermarks or real official marks. 1:1 square edge-to-edge artwork, not a product mockup.

Los JPEG utilizados por la app están en `app/src/main/assets/gatuna/`. El texto de cada publicación está en `GatunaTopic.kt`.

## Generar nuevas imágenes desde el teléfono

Conecta el motor en Ajustes → Cuenta de ChatGPT → Codex para imágenes. Es el mismo enfoque que usa Lámina. El ejecutable oficial ARM64 de Codex se inicia como proceso local de Android y recibe la solicitud por entrada/salida estándar. La sesión es propia de esta app. Usa tu cuenta de ChatGPT y completa la autorización de Codex. El motor utiliza su herramienta integrada ImageGen y guarda la imagen en Recursos. La app usa la red del teléfono para hablar con OpenAI. [Protocolo de Codex app-server](https://developers.openai.com/codex/app-server/).

En el menú del agente, elige «Generar imagen» y describe el arte. La generación integrada de Codex es distinta de la conexión de la app para texto mediante «Iniciar sesión con ChatGPT», que excluye la herramienta de imágenes. Existe también «Copiar encargo y abrir ChatGPT». En ese flujo descargas la pieza y la adjuntas con el clip de la campaña. [Limitaciones de la conexión de texto](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
