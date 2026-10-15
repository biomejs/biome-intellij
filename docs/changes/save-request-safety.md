# Save request safety

Each supported file gets one five-second budget for safe fixes, import sorting, and formatting, in that order. A timeout or ordinary request failure stops work on that file while the remaining files continue. Edits already completed remain available to the IDE's normal save.

The service checks cancellation and the document's modification stamp inside the write command. A response cannot overwrite typing that happened while the request was pending. Cancellation and platform control-flow exceptions leave the operation without an error notification.

Only immediate code-action edits without a command are applied. Resolving an action through the native intention API can issue a synchronous request, and command completion is not tracked here. Those actions are skipped so the write section does not start another network operation.

The client omits deferred code-action edit support from its initialization capabilities. Biome 2.5.15 otherwise returns actions without edits, which this bounded path cannot apply. Biome computes immediate edits when that capability is absent; native lightbulb actions are computed eagerly too. Safe fixes and import sorting remain separate requests, so safe fixes alone retain import order.

The save fixture pins Biome 2.5.15. Its first-open cases check the SDK initialization capabilities, safe fixes with import sorting disabled, and import sorting on its own. The existing highlighting fixture retains its earlier server version as a compatibility control.

This change retains the existing modal save hook and native line-separator converter. The platform's suspending save lifecycle is a separate change with its own separator persistence and undo tests.

Run the focused checks with:

```sh
./gradlew cleanTest test --tests '*BiomeSaveOperationTest' --tests '*BiomeLegacySaveActionTest' --tests '*BiomeSaveActionsTest' --tests '*UnusedFunctionHighlightingTest' buildPlugin --no-build-cache
```
