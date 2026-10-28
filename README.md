# Portfolio Intelligence AI - Native Android App

Production-ready Jetpack Compose codebase with GitHub Cloud Builder workflow.

## 6-File Minimalist Architecture
1. `settings.gradle.kts` - Repositories and root project config
2. `build.gradle.kts` - Gradle plugins (Android 8.7.2, Kotlin 2.0.21, Compose)
3. `app/build.gradle.kts` - App dependencies, compileSdk 35, Jetpack Compose 2024.10.01 BoM
4. `app/src/main/AndroidManifest.xml` - Permissions & MainActivity configuration
5. `app/src/main/java/com/portfolio/intelligence/AppCode.kt` - Complete native app codebase (Holdings, CSV processor, ViewModel, 7 Screens)
6. `.github/workflows/build-apk.yml` - Cloud CI/CD workflow that compiles debug APK with Java 17 and Gradle 8.10.2

## Automatic Cloud Build via GitHub Actions
Pushing to `main` or `master` triggers `.github/workflows/build-apk.yml` to assemble `app-debug.apk` using Java 17 and Gradle 8.10.2 in ~2 minutes. Download the artifact from GitHub Actions to install on your phone.
