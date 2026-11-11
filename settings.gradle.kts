pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}


dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // sherpa-onnx (on-device Kokoro TTS) is published to JitPack only
    maven("https://jitpack.io") { content { includeGroup("com.github.k2-fsa.sherpa-onnx") } }
  }
}

rootProject.name = "OmniRead"

include(":app")
