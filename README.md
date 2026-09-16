# Pocket Watch Recorder

An Android **Wear OS** app that replicates the Pocket AI device: hold the watch,
record, and get the transcript and summary back from the Pocket public API using
your own API key.

## Setup

1. Copy `local.properties.example` to `local.properties`.
2. Fill in `sdk.dir` and `POCKET_API_KEY`.
3. `./gradlew installDebug` with a watch (or Wear emulator) attached.

`local.properties` is gitignored and must stay that way — it holds your key.
There is no in-source fallback constant to paste a key into, deliberately.

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
