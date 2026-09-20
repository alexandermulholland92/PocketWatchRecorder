# Pocket Watch Recorder

An Android **Wear OS** app that replicates the Pocket AI device: hold the watch,
record, and get the transcript and summary back from the Pocket public API using
your own API key.

## Setup

1. Copy `local.properties.example` to `local.properties`.
2. Fill in `sdk.dir` and `POCKET_API_KEY`.
3. `./gradlew installDebug` with a watch (or Wear emulator) attached.

`local.properties` is gitignored and must stay that way. You can also
`export POCKET_API_KEY=...` instead of putting it in the file.

### Or set the key on the watch

A build-time key is optional. **Settings → Pocket key** stores it encrypted on
the device (AES-GCM under a hardware-backed Keystore key), and a key entered
there takes precedence over anything baked in. Two ways to get it in:

- **Enter key** uses Wear's own input activity — watch keyboard, voice, or the
  phone's keyboard where the watch offers it.
- **Type from phone** serves a one-page form on the local network. The watch
  shows a URL and a PIN; open the URL in your phone's browser, type, and it
  lands on the watch. Both devices must be on the same Wi-Fi — over the
  Bluetooth proxy the phone has no route to the watch, and the watch will say
  so rather than show an address nothing can reach.

  The listener runs only while that screen is open, on a random port, behind
  the PIN, and stops the moment one valid submission arrives. The text does
  cross the LAN as plaintext HTTP, protected only by your Wi-Fi encryption.

Prefer this for any build you publish. A key baked in at build time ships in
the APK as a plaintext constant, so the APK becomes a credential: you cannot
put it in a GitHub release, hand it to anyone, or rotate without rebuilding.

> **Upgrading from before this branch: your key is gone.** `local.properties`
> used to be tracked, and `PocketNetwork.kt` used to carry an `API_KEY_FALLBACK`
> constant you could paste a key into. Both are removed, so pulling this branch
> takes your key out of the build. Either recreate `local.properties` as above,
> or set the key on the watch.

## Getting an APK onto your watch

**Actions → APK → Run workflow** builds one and attaches it to a prerelease, so
you get a direct `.apk` link you can open on your phone.

That button only appears once this workflow is on the default branch — GitHub
does not offer `workflow_dispatch` for a workflow it can only see on a feature
branch. Until then, or any time you prefer it, push a tag:

```bash
git tag apk-v1 && git push origin apk-v1
```

| Input | Use |
|---|---|
| `variant` | `debug` needs no setup and installs straight away. `release` is smaller and R8-shrunk, but needs signing secrets (the workflow tells you which). |
| `publish` | `release` gives a direct `.apk` URL. `artifact` gives a zip. |
| `bake_api_key` | Leave off. On it puts your key in the APK, and anyone who can download the APK can read it. |

The debug signing key is cached between runs, so successive builds install over
each other instead of failing with "app not installed".

This is a **watch** app — it declares `android.hardware.type.watch` as required,
so it will not install on a phone. Download it on the phone, then push it to the
watch with ADB over Wi-Fi or a sideloading app.

> **If you cloned this before the key was removed:** the repository's history
> contains a real `pk_...` key. Rotate it in Pocket. Deleting the file from the
> tip of the branch does not remove it from history, and GitHub serves
> unreachable blobs for a long time afterwards.

## How it works

A recording moves through four stages, each owned by something that can survive
the UI going away:

| Stage | Owner | Why there |
|---|---|---|
| Capture | `audio/RecordingService` | A foreground service with `microphone` type. From API 30 on, a non-foreground process is handed silence rather than an error. |
| Queue | `upload/UploadQueue` | A JSON sidecar per recording. Provisioning is not idempotent, so the recording id is written to disk *before* the PUT — a retry then resumes instead of creating a duplicate. |
| Transfer | `upload/UploadWorker` | WorkManager, so the system can wake a frozen app. Promotes itself to a foreground service so a slow transfer outlives the ~10 minute worker budget. |
| Summary | `RecorderViewModel` | One tracker coroutine per entry, so starting a new recording never abandons the previous one. |

`network/PocketResponses.kt` holds the shape-tolerant readers for the API, which
returns the same field under different names and at different depths across
deployments. That file is where the parsing bugs have been, so it is plain
Kotlin with no Android dependencies and it is unit tested.

## Tests

```bash
./gradlew testDebugUnitTest
```

JVM tests only — they cover the response parsing, the queue's state rules and
the timestamp formatting. There are no instrumented tests yet.
