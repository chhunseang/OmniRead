# Third-party notices

OmniRead is licensed under the GNU GPL v3.0 (see [LICENSE](LICENSE)). It includes or downloads
the following third-party components, each under its own licence.

## Bundled in the app

| Component | Licence | Notes |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 2.0.1 | Apache-2.0 | Vendored as `app/libs/sherpa-onnx-2.0.1.aar` (includes ONNX Runtime, MIT) |
| [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) | BSD-3-Clause | |
| [AndroidX / Jetpack](https://developer.android.com/jetpack) (Compose, Media3, Room, WorkManager, DataStore…) | Apache-2.0 | |
| [Dagger Hilt](https://dagger.dev/hilt/) | Apache-2.0 | |
| [Kotlin & kotlinx](https://kotlinlang.org/) (coroutines, serialization) | Apache-2.0 | |
| [OkHttp](https://square.github.io/okhttp/), [Retrofit](https://square.github.io/retrofit/), [Moshi](https://github.com/square/moshi) | Apache-2.0 | |
| [Coil](https://coil-kt.github.io/coil/) | Apache-2.0 | |
| [jsoup](https://jsoup.org/) | MIT | |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache-2.0 | |
| [Literata](https://github.com/googlefonts/literata) | SIL OFL 1.1 | `app/src/main/res/font/literata.ttf` |
| [Playfair Display](https://github.com/clauseggers/Playfair) | SIL OFL 1.1 | `app/src/main/res/font/playfair_display.ttf` |

## Downloaded at runtime (not in the APK)

| Component | Licence | Notes |
|---|---|---|
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (sherpa-onnx int8 multi-lang v1.0 export) | Apache-2.0 | Fetched once from the official sherpa-onnx GitHub release when you install the voices |
