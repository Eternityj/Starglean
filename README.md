# Starglean

Starglean is a native Android journaling app that turns everyday memories into a personal sky of stars. Write a diary entry, keep it private or share it with nearby readers, and revisit your memories through a growing collection of constellations.

Built with Kotlin and Jetpack Compose, the app combines a celestial interface with location-based discovery, weather snapshots, and device motion. The current phone interface is in English; saved diary content keeps its original language.

## Features

- **Write and preview memories.** Compose entries with an optional title, paper and font choices, and up to two photos. Drafts are stored locally with recovery support.
- **Choose how to share.** Save an entry to the cloud as Public or Private after reviewing the preview and confirming the upload. A successful save also keeps a copy in your account's library on the device.
- **Discover nearby writing.** An intentional shake starts a search for eligible public entries within 200 metres. The app supports reactions, reports, and blocking, with a server-enforced limit of 10 confirmed opens per account day.
- **Build your constellations.** Saved entries light stars in eight constellation types. Reopen memories from their stars and choose completed constellations to display on your Planet.
- **Remember the setting.** Capture weather with an entry and use the optional Memory Compass to point toward a location saved with your own memory.
- **Restore and sync by choice.** Restore entries from the Cloud library and opt into constellation-layout sync across devices.
- **Export a private backup.** Create a password-encrypted backup of saved local entries and photos, then review its contents before importing. Backups exclude drafts, constellation layouts, and sign-in credentials.

## Explore the app

| Screen | What you can do |
| --- | --- |
| **Planet** | View your personal sky and open settings for your account, location, backups, sync, feedback, and privacy. |
| **Discover** | Shake to find nearby public writing, read a note, and respond or report it. |
| **Write** | Continue a local draft, attach photos, preview the entry, and choose Public or Private cloud saving. |
| **My constellations** | Browse your stars, revisit saved entries, and manage your constellation collection. |

## Build and run

### Requirements

The versions below come from the checked-in build configuration:

| Component | Project configuration |
| --- | --- |
| Android device or emulator | Android 8.0 / API 26 or later |
| Android SDK | Compile and target API 37 |
| Gradle wrapper | 9.5.0 |
| Android Gradle Plugin | 9.3.1 |
| Gradle daemon JVM | JetBrains JDK 21 |
| Java source and target compatibility | 17 |
| Compose compiler plugin | 2.2.10 |

Use Android Studio with support for the configured Android Gradle Plugin. Install SDK Platform 37 and Android SDK Platform-Tools through the SDK Manager. The repository's daemon JVM criteria select JDK 21; the Java 17 setting controls source and target compatibility.

### Debug build

1. Clone the repository:

   ```sh
   git clone https://github.com/Eternityj/Starglean.git
   cd Starglean
   ```

2. Open the project root in Android Studio and let Gradle sync. Configure the SDK location through Android Studio, or create a local, untracked `local.properties` file containing `sdk.dir=/absolute/path/to/your/android-sdk`.
3. Select the `app` module and the `debug` build variant, then run it on a device or emulator. To build from a terminal:

   ```sh
   ./gradlew :app:assembleDebug
   ```

   On Windows, use `gradlew.bat` instead of `./gradlew`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

A physical Android device with Google Play services is useful for checking motion, precise location, and compass behavior. Feature availability depends on permissions and device sensors.

### First use and backend configuration

- First use requires an email account and an eight-digit verification code. Normal debug and release builds use the real sign-in flow.
- Returning users with a previously verified session can access local memories and drafts during a temporary service outage. Cloud actions still require server authorization.
- Declining location access allows draft writing. Nearby discovery and new cloud saves require precise foreground location, consent, and a reachable backend. The app does not request background location permission.
- The build reads the project URL and publishable key from [`supabase/client-config.properties`](supabase/client-config.properties). Building the APK does not provision the database, Edge Function, or email delivery. Cloud features require a compatible configured backend.

Backend sources and maintenance guidance are described in the [backend README](supabase/README.md). Never put service-role keys, administrator credentials, or signing secrets in the app or repository.

### Release builds

Release builds use the application ID `com.starglean.app.local` and require an external signing configuration. The build accepts its location through `STARGLEAN_SIGNING_PROPERTIES`; the file must provide `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Updates to an existing installation must use its original signing identity. Debug builds do not require this private signing configuration.

See [`app/build.gradle.kts`](app/build.gradle.kts) for the signing checks and release settings.

## Verification

Run the Android JVM unit tests and debug lint checks:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The operator CLI has a separate fake-client test suite that needs Node.js and does not contact the backend:

```sh
node --test tools/operator/starglean.test.mjs
```

For the scripted device checks, create two **disposable** Android Virtual Devices named `Starglean_QA` and `Starglean_Location_QA`, and ensure `local.properties` contains the SDK path. Set `JAVA_HOME` to a valid JDK installation if Android Studio is not installed at the script's default macOS path, `/Applications/Android Studio.app/Contents/jbr/Contents/Home`. Then run:

```sh
bash tools/device-tests/run-device-tests.sh
```

This script builds debug APKs, wipes the named emulators' data, and runs selected instrumented test flows. It does not enable the live-service test flags. Logs are saved under `app/build/device-test-results/`. Review the [device test script](tools/device-tests/run-device-tests.sh) before running it; additional opt-in and historical-version upgrade tests have separate prerequisites.

## Repository guide

| Path | Contents |
| --- | --- |
| [`app/src/main/app/`](app/src/main/app/) | Application entry point, Kotlin business logic, Compose screens, components, and sky rendering. |
| [`app/src/main/res/`](app/src/main/res/) | App artwork, Android resources, and platform configuration. |
| [`app/src/main/privacyPolicy/`](app/src/main/privacyPolicy/) | The privacy notice packaged with the app. |
| [`app/src/test/`](app/src/test/) | JVM unit tests for models, storage rules, protocols, and UI policies. |
| [`app/src/androidTest/`](app/src/androidTest/) | Android device and emulator tests. |
| [`supabase/`](supabase/) | Database migrations, contract checks, private-media Edge Function, and email templates. |
| [`tools/`](tools/) | Operator CLI, API checks, device-test runner, and fictional test fixtures. |

## Data and privacy

Drafts and local libraries are separated by signed-in account. Opening an account does not automatically upload those files. Public and Private cloud saves both upload the selected content and its precise location; cloud content is not end-to-end encrypted. Nearby readers are not sent the author's identity or precise coordinates.

Constellation sync is opt-in and does not upload local diary text or photos. The Memory Compass uses your own saved locations. For the full explanation of permissions, retention, deletion, backups, and sharing, read the [in-app privacy notice source](app/src/main/privacyPolicy/privacy-notice.json).

## Further documentation

- [Backend files and migration guidance](supabase/README.md)
- [Backend operations and deployment notes](supabase/OPERATIONS.md)
- [Authentication email templates](supabase/email-templates/README.md)
- [Operator CLI](tools/operator/README.md)
- [Read-only API denial checks](tools/supabase-check/README.md)
