# Handoff: docs-anr diagrams after report delivery deepening

## Current focus

The next session should update the ANR SDK documentation diagrams under `docs-anr/` so they match the latest code after the report delivery architecture deepening.

The user asked whether these files need updates:

- `docs-anr/108-SDK项目架构图.html`
- `docs-anr/109-SDK时序图.html`
- `docs-anr/110-AnrMonitor-install函数调用链路图.html`

The conclusion already given to the user: **yes**. `110` must be updated, `109` should be updated, and `108` should be synchronized to show the new module boundary.

## Relevant commits

Do not duplicate the implementation diff in this handoff. Use these commits as the source of truth:

- `542c5b4 refactor: deepen ANR report delivery`
- `cdc7d4d chore: commit project agent metadata`

At the time this handoff was created, `git status --short` was clean.

## Code facts to preserve

Latest code moved report delivery details out of `AnrMonitorRuntime`:

- `AnrMonitorRuntime` now owns one `AnrReportDelivery`.
- `AnrMonitorRuntime.start()` calls `reportDelivery.startRetryLoop(isRunning = { isRunning.get() })`.
- `AnrMonitorRuntime.stop()` calls `reportDelivery.stopRetryLoop()`.
- `captureAndReport()` calls:
  - `reportDelivery.writeLocalReport(report = report)`
  - `reportDelivery.uploadReportIfEnabled(report = report)`
- `AnrMonitorRuntime` no longer directly owns:
  - `AnrReportJsonEncoder`
  - `LocalAnrReportWriter`
  - `ReportRetryQueue`
  - `ReportRetryDispatcher`
  - upload retry thread fields/functions
- `ReportRetryDispatcher` now tracks `inFlightFileNames` so first upload and retry flush do not upload the same report concurrently.

Relevant source files:

- `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
- `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/delivery/AnrReportDelivery.kt`
- `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/delivery/AnrReportDeliveryFactory.kt`
- `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/retry/ReportRetryDispatcher.kt`
- `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/delivery/AnrReportDeliveryTest.kt`

## Documentation update notes

### `docs-anr/108-SDK项目架构图.html`

This file is high-level and not deeply wrong, but it should be updated to show the new `AnrReportDelivery` module in the reporter layer.

Current outdated wording includes:

- Reporter layer text like `Encoder / LocalWriter / RetryQueue`

Suggested change:

- Show reporter layer as `AnrReportDelivery`
- Under it or inside the same layer, show implementation details such as `Encoder / LocalWriter / RetryQueue / Dispatcher`
- Keep the architecture language aligned with the previous review: `AnrReportDelivery` is an internal implementation module, not a new public interface.

### `docs-anr/109-SDK时序图.html`

This file needs a smaller update.

Current outdated mapping says report write is implemented directly by:

- `LocalAnrReportWriter.write`

Suggested sequence:

- `AnrMonitorRuntime.captureAndReport`
- `AnrReportDelivery.writeLocalReport`
- `LocalAnrReportWriter.write`

If upload is represented, include:

- `AnrReportDelivery.uploadReportIfEnabled`
- `ReportRetryDispatcher.enqueueReport`
- host `AnrReportUploader.upload`
- `ReportRetryDispatcher.recordUploadResult`
- note that `ReportRetryDispatcher` skips `inFlightFileNames` during retry flush.

### `docs-anr/110-AnrMonitor-install函数调用链路图.html`

This file is clearly stale and should be updated first.

Known outdated content:

- `AnrMonitorRuntime(...)` tree still shows direct construction of:
  - `AnrReportJsonEncoder`
  - `LocalAnrReportWriter`
  - `ReportRetryQueue`
  - `ReportRetryDispatcher`
- Start chain still says:
  - `startUploadRetryLoop()`
  - `Thread(::runUploadRetryLoop, "vibe-anr-upload-retry").start()`
- Capture/report chain still says:
  - `localWriter.write(report)`
  - `uploadIfEnabled(report)`
- Chapter 2 still lists:
  - `reportAssembler / reportEncoder / localWriter`
  - `reportRetryQueue / reportRetryDispatcher`
- Chapter 7 still frames the route as:
  - `localWriter.write(report)`
  - `uploadIfEnabled(report)`
  - `uploadIfEnabled()` in `internal/AnrMonitorRuntime.kt`

Suggested replacement concepts:

- Constructor tree:
  - `AnrReportDelivery(...)`
  - `AnrReportDeliveryFactory.createAndroidParts(...)`
  - `LocalAnrReportWriter(...)`
  - `ReportRetryQueue(...) / ReportRetryDispatcher(...)`
- Start chain:
  - `reportDelivery.startRetryLoop(isRunning = { isRunning.get() })`
- Stop chain:
  - `reportDelivery.stopRetryLoop()`
- Capture/report chain:
  - `reportDelivery.writeLocalReport(report)`
  - `listener.onConfirmedAnr(report)`
  - `reportDelivery.uploadReportIfEnabled(report)`
  - failure result maps to `listener.onMonitorError(...)`
- Upload sub-chain:
  - `ReportRetryDispatcher.enqueueReport(...)`
  - `uploader.upload(report)`
  - `ReportRetryDispatcher.recordUploadResult(...)`
  - `flushDueReports(...)` skips in-flight file names to avoid duplicate upload.

## Verification already performed

Before this handoff, the SDK implementation was verified with:

- `./gradlew :anr-monitor-sdk:testDebugUnitTest`
- `./gradlew :app:compileDebugKotlin`

For documentation changes, the next agent should at least run a static search for stale names after editing:

```bash
rg -n "startUploadRetryLoop|runUploadRetryLoop|uploadIfEnabled\\(|localWriter\\.write|reportRetryQueue|reportRetryDispatcher|reportEncoder" docs-anr/108-SDK项目架构图.html docs-anr/109-SDK时序图.html docs-anr/110-AnrMonitor-install函数调用链路图.html
```

If the HTML is opened or rendered, inspect it visually for broken layout and overlapping text.

## Suggested skills

- `handoff`: only if creating another compact transfer note later.
- `browser:control-in-app-browser`: use if verifying rendered HTML in the in-app browser is possible.
- `improve-codebase-architecture`: use if continuing to describe module/interface/implementation/depth/locality language in docs.
- `karpathy-guidelines`: use for surgical documentation edits and avoiding broad unrelated rewrites.

## Constraints and cautions

- Do not create `CONTEXT.md` or ADRs unless the user explicitly asks or the terminology becomes stable enough to record.
- Avoid changing Kotlin code unless the user asks. If Kotlin code is modified, follow project instructions: run GitNexus impact before symbol edits and use the relevant Kotlin/TDD skills.
- Keep doc changes focused on the delivery module refactor; do not rewrite unrelated ANR scenario or collector documentation.
- No sensitive values were included in this handoff.
