# Platform save lifecycle

Biome now updates each document through the platform's suspending save action. The IDE owns the final save and cancels the pending work when the user types or closes the project. The per-document request budget and stale-response checks remain in place.

The native line-separator converter saves the document before conversion, which conflicts with this lifecycle. Text edits instead carry separator metadata into the platform's final save. Separator-only edits convert bytes directly because the final save skips equal normalized text. Both paths compare VFS and filesystem timestamps. If they differ, separator undo and redo report a conflict and leave the external bytes untouched.

A successful formatting response is applied to the normalized request text to reconstruct the complete server output. Separator detection uses that output even when the server returns an empty edit list or only whitespace edits. A null response leaves text and separators unchanged. Replacement normalization accounts for CRLF pairs split across edit boundaries while retaining the server's edit ranges. Building the output takes linear work after ordering the edits; it does not compare the whole document again.

`BiomeSavePlatformLifecycleTest` covers the actual save hook, IDE formatter ordering, typing and disposal cancellation, LF and CRLF bytes, separator-only edits, combined text and separator undo, and external writes. It includes a normal document-save conflict control, real Biome 2.5.15 CRLF-to-LF responses with no newline-bearing edits, and undo/redo for those conversions. Synthetic split-CRLF cases check unchanged-text and adjacent-edit boundaries; current Biome probes emit complete CRLF chunks. A 12,000-line response based on Biome 2.5.15 output checks application below the default file limit without depending on server diff latency. The inherited save request tests retain batch failure isolation and cancellation checks.

Run the focused checks with:

```sh
./gradlew cleanTest test --tests '*BiomeSavePlatformLifecycleTest' --tests '*BiomeSaveActionsTest' --tests '*BiomeSaveOperationTest' --tests '*BiomeLegacySaveActionTest' --tests '*UnusedFunctionHighlightingTest' buildPlugin --no-build-cache
```
