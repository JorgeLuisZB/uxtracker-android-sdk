# UxTracker Android SDK

Native Android SDK for [UxTracker](https://github.com/JorgeLuisZB) product analytics. It implements the
UxTracker ingestion protocol v1 (`docs/spec/ingestion-protocol-v1.md` in the backend repository): events are
stored on disk, sent in order, retried with backoff, and never block or crash your app.

- **Min SDK:** 21 · **Dependencies:** none · **Size:** a few hundred KB

## Install

With [JitPack](https://jitpack.io), in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and in your app module:

```kotlin
dependencies {
    implementation("com.github.JorgeLuisZB:uxtracker-android-sdk:1.0.0-beta.1")
}
```

## Use

Initialize once, in `Application.onCreate`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UxTracker.initialize(
            this,
            UxTrackerConfig.Builder(
                writeKey = "uxt_pk_live_mx_…",               // project write key
                serverUrl = "https://ingest.mx.example.com",  // your project's region
            )
                .debug(BuildConfig.DEBUG)
                .build(),
        )
    }
}
```

Then anywhere:

```kotlin
UxTracker.track("Menu day selected", mapOf("day" to "monday", "menu_id" to 1234))
UxTracker.screen("Dashboard")

UxTracker.identify("user_98231", mapOf("plan" to "premium"))   // on login; never an email or phone
UxTracker.group("clinic", "clinic_417", mapOf("name" to "Clínica Centro"))
UxTracker.register(mapOf("app_theme" to "dark"))              // added to every later event

UxTracker.reset()                                              // on logout
UxTracker.optOut() / UxTracker.optIn()                         // user consent
UxTracker.flush()                                              // send now (normally automatic)
```

Java works the same way: `UxTracker.track("Menu day selected", properties);`.

## Configuration

| Builder method | Default | |
|---|---|---|
| `flushIntervalSeconds` | 30 | Automatic send interval (min 5) |
| `flushAt` | 20 | Send when this many events are queued (1–100) |
| `maxQueueSize` | 10,000 | Oldest events are dropped beyond it |
| `sessionTimeoutSeconds` | 1,800 | Time in background that starts a new session |
| `trackAppLifecycle` | true | `$app_installed`, `$app_updated`, `$app_opened`, `$app_backgrounded` |
| `optOutByDefault` | false | Send nothing until `optIn()` |
| `debug` | false | Verbose `UxTracker` logs; allows `http://` servers |

## What it collects

`context.library`, `app` (name, version, build, package), `device` (manufacturer, model, type), `os`, `screen`,
`locale`, `timezone`, and `network` only if your app already holds `ACCESS_NETWORK_STATE`. It never collects
advertising ids, Android ID, IP addresses, location or contacts, and never requests permissions.

## Behaviour

- Events are written to a SQLite queue on a background thread and sent gzipped in batches of up to 100.
- Sending happens every `flushIntervalSeconds`, when `flushAt` events are queued, and when the app goes to
  the background.
- Server errors and network failures retry with exponential backoff (up to 5 minutes); `429` waits for
  `Retry-After`; an invalid key stops sending until the next launch without deleting events.
- Invalid properties (unsupported types, NaN, keys starting with `$`, nesting deeper than 3) are dropped with a
  warning; strings over 8,192 characters are truncated.

## For wrapper SDKs

Flutter and React Native wrappers call `UxTracker.setWrapper("uxtracker-flutter", version)` before
`initialize`, so events report the wrapper as `context.library` with this SDK as `library.core`.

## Development

```bash
./gradlew :uxtracker:testDebugUnitTest          # JVM + Robolectric tests
UXTRACKER_E2E_SERVER_URL=http://localhost:7002 UXTRACKER_E2E_WRITE_KEY=uxt_pk_test_mx_… \
  ./gradlew :uxtracker:testDebugUnitTest --tests '*LiveBackendTest*'   # against a running backend
```

Releases: bump `uxtrackerVersion` in `gradle.properties`, tag it (e.g. `1.0.0-beta.1`) and JitPack builds it.
