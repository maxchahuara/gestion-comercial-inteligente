# Componentes de Gestión Comercial Inteligente

La generación de imágenes utiliza el ejecutable oficial Codex 0.166.0 para Linux ARM64 (Apache-2.0). La integración de proceso y red está adaptada del proyecto Lámina. Las licencias y avisos de Codex se incluyen en `app/src/main/assets/licenses/`. [Release del ejecutable](https://github.com/openai/codex/releases/tag/rust-v0.166.0).

El código original de Gestión Comercial Inteligente se distribuye bajo MIT. Consulta [LICENSE](LICENSE). La adaptación de Lámina Android conserva Apache-2.0 en CodexImageEngine.kt y AndroidNetwork.kt. Los servicios externos de ChatGPT y las redes sociales no forman parte de esa licencia.

| Componente incorporado | Licencia principal | Fuente |
|---|---|---|
| Kotlin y plugin Compose | Apache-2.0 | https://github.com/JetBrains/kotlin |
| AndroidX Core, Activity, Compose, Lifecycle, Room y WorkManager | Apache-2.0 | https://github.com/androidx/androidx |
| kotlinx.coroutines | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| Nimbus JOSE JWT | Apache-2.0 | https://connect2id.com/products/nimbus-jose-jwt |
| Vosk Android 0.3.75 | Apache-2.0 | https://github.com/alphacep/vosk-api |
| Modelo español vosk-model-small-es-0.42 | Apache-2.0 | https://alphacephei.com/vosk/models |
| JNA 5.18.1 | Apache-2.0, opción elegida de su licencia dual | https://github.com/java-native-access/jna |
| Guava listenablefuture, transitivo de AndroidX | Apache-2.0 | https://github.com/google/guava |
| JetBrains annotations | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| JSpecify | Apache-2.0 | https://github.com/jspecify/jspecify |
| Procedimientos inspirados y adaptados de marketingskills | MIT | https://github.com/coreyhaines31/marketingskills |
| JUnit 4, solo pruebas | EPL-1.0 | https://github.com/junit-team/junit4 |
| Gradle, solo compilación | Apache-2.0 | https://github.com/gradle/gradle |

La licencia MIT de marketingskills se conserva también en app/src/main/assets/licenses/marketingskills-MIT.txt. Las ocho skills empaquetadas son adaptaciones para herramientas locales y no incorporan los CLIs ni servicios de sus ejemplos.

Los avisos y la licencia Apache de Vosk, su modelo y JNA se incluyen en assets/licenses. El modelo original sin modificar se incorpora al APK durante la compilación, después de verificar su SHA-256. No se distribuyen servicios de transcripción ni un runtime Python.

La red se implementa con HTTPS de Android y un cliente propio de Responses/SSE. El inicio de sesión es una implementación original del protocolo documentado. No se incorpora el DevKit no comercial de Sign in with ChatGPT, código de Tinti o Teclaro, clientes vinculados de WhatsApp ni SDKs de integración social.

Los archivos POM y los avisos de los componentes transitivos deben conservarse al redistribuir sus artefactos. La lista exacta resuelta se obtiene con gradlew :app:dependencies --configuration releaseRuntimeClasspath. El SDK y el JDK utilizados para compilar tienen sus propios avisos y condiciones; no se incluyen como runtimes adicionales en el APK.
