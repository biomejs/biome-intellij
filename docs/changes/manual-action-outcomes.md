# Manual action outcomes

Apply Safe Fixes and Sort Imports now report whether edits changed the document, nothing needed changing, the server was unavailable, a response became stale, or the platform declined an action. A successful native availability check does not prove its write preparation succeeded, so the service tracks the actual workspace-edit callback. Commands and actions that need synchronous resolution remain outside the bounded request path.

The shared save request budget distinguishes its own timeout from parent or platform cancellation. Cancellation propagates without a failure notification. A mixture of document changes and declined actions produces partial-result feedback.

`BiomeManualActionsTest` covers both actions, native write preparation, empty and null responses, real Biome 2.5.15 edits and undo, stale responses, failures, timeout, and cancellation. The real-server case requires diagnostics on the first document open. Run it with `./gradlew test --tests '*BiomeManualActionsTest'`. This unit depends on save request safety and retains the legacy save hook and native line-separator conversion.
