# Changelog

All notable changes to this project will be documented in this file.

## 1.11.2 - 2026-10-27

- Resubmit the plugin to JetBrains Marketplace. Plugin behavior is unchanged from 1.11.1.

## 1.11.1 - 2026-10-21

### Documentation

- Record the 1.11.0 release changes. Plugin behavior is unchanged from 1.11.0.

## 1.11.0 - 2026-10-21

Requires IntelliJ IDEA Ultimate or WebStorm 2025.3 or newer.

### Bug fixes

- Don't restart the language server when applying settings for the default project ([#240](https://github.com/biomejs/biome-intellij/pull/240))
- Keep the configuration file path in the settings ([#241](https://github.com/biomejs/biome-intellij/pull/241))
- Isolate save requests and reject stale edits ([#248](https://github.com/biomejs/biome-intellij/pull/248))
- Report manual action outcomes and reject late edits ([#249](https://github.com/biomejs/biome-intellij/pull/249))
- Run Biome through the platform save lifecycle ([#251](https://github.com/biomejs/biome-intellij/pull/251))
- Render diagnostic codes and tooltip text safely ([#260](https://github.com/biomejs/biome-intellij/pull/260))
- Close config streams and preserve cancellation ([#262](https://github.com/biomejs/biome-intellij/pull/262))
- Skip Biome startup for ineligible files ([#263](https://github.com/biomejs/biome-intellij/pull/263))
- Use the configured Node interpreter for manual launchers ([#264](https://github.com/biomejs/biome-intellij/pull/264))
- Update dependency com.google.code.gson:gson to v2.14.0 ([#299](https://github.com/biomejs/biome-intellij/pull/299))

### Features

- Remove the version indicator on Actions on Save page ([#221](https://github.com/biomejs/biome-intellij/pull/221))
- Add `.scss`, `.gs`, `.htm`, `.svg`, `.md`, `.markdown`, `.yml`, and `.yaml` to the default extension list ([#239](https://github.com/biomejs/biome-intellij/pull/239)). Available features depend on the installed Biome version. Existing saved extension lists are preserved; use Reset to Defaults in Biome settings to adopt the new list.

## 1.10.1

### Bug Fixes

- Resolve the `extends` option in the config correctly ([#214](https://github.com/biomejs/biome/pull/214))

## 1.10.0

### Bug Fixes

- Revert a change to use `DocumentUtil.executeInBulk` ([#208](https://github.com/biomejs/biome-intellij/pull/208))
- Fallback to child config when no root config was found in the project ([#210](https://github.com/biomejs/biome-intellij/pull/210))

### Features

- Improve diagnostics message ([#209](https://github.com/biomejs/biome-intellij/pull/209))

## 1.9.0

### Bug Fixes

- Support project structure with multiple roots ([#197](https://github.com/biomejs/biome-intellij/pull/197))

### Features

- Support the new-style monorepo ([#196](https://github.com/biomejs/biome-intellij/pull/196))

## 1.8.1

### Bug Fixes

- Resolved compatibility problems for older versions of IntelliJ IDEs ([#193](https://github.com/biomejs/biome-intellij/pull/193))

## 1.8.0

### Bug Fixes

- Fixed that applying fixes on save can break the code in the editor ([#190](https://github.com/biomejs/biome-intellij/pull/190))

### Features

- Removed a file listener that watches changes to `biome.json(c)`, it is now handled by Biome itself ([#183](https://github.com/biomejs/biome-intellij/pull/183))
- Improved performance on formatting ([#184](https://github.com/biomejs/biome-intellij/pull/184))

## 1.7.2

### Bug Fixes

- Fixed a regression where the plugin couldn't find biome.json at the project root ([#166](https://github.com/biomejs/biome-intellij/pull/166))

## 1.7.1

### Bug Fixes

- Avoid duplicated LSP instances in monorepo ([#159](https://github.com/biomejs/biome-intellij/pull/159))

## 1.7.0

### Bug Fixes

- Support textDocument/formatting response with granular text edits ([#148](https://github.com/biomejs/biome-intellij/pull/148))
- Run text edits in a reversed order ([#151](https://github.com/biomejs/biome-intellij/pull/151))
- Avoid conflicting code actions on save ([#149](https://github.com/biomejs/biome-intellij/pull/149))

### Features

- Monorepo support ([#138](https://github.com/biomejs/biome-intellij/pull/138))
- Support workspace/configuration request for providing configuration path ([#144](https://github.com/biomejs/biome-intellij/pull/144))

## 1.6.0

### Bug Fixes

- Improve BiomePackage and LSP startup logic ([#129](https://github.com/biomejs/biome-intellij/pull/129))
- Support running Biome on WSL Node.js interpreter ([#131](https://github.com/biomejs/biome-intellij/pull/131))
- Convert WSL path to local path and vice versa ([#132](https://github.com/biomejs/biome-intellij/pull/132))
- Workaround for IDEA-347138 ([#135](https://github.com/biomejs/biome-intellij/pull/135))

### Features

- Support running custom Biome executable on WSL 2 ([#134](https://github.com/biomejs/biome-intellij/pull/134))
- Refactor and replace BiomeRunner with BiomeServerService ([#124](https://github.com/biomejs/biome-intellij/pull/124))

## 1.5.5

### Bug Fixes

- Keep empty end line ([#120](https://github.com/biomejs/biome-intellij/pull/120))

### Features

- Add support for Biome config icons and improve save actions ([#119](https://github.com/biomejs/biome-intellij/pull/119))


## 1.5.4

### Bug Fixes

- Revert "format on save" ([#117](https://github.com/biomejs/biome-intellij/pull/117))

## 1.5.3

### Bug Fixes

- Infinite indexing / intellisense blocked ([#102](https://github.com/biomejs/biome-intellij/pull/102))
- Resolve plugin crash when using nightly biome releases ([#112](https://github.com/biomejs/biome-intellij/pull/112))

## 1.0.0

### Bug Fixes

- Fix plugin for IntelliJ 2024.1 ([#45](https://github.com/biomejs/biome-intellij/pull/45))
- Added missing double quotes ([#377](https://github.com/biomejs/biome-intellij/pull/377))
- Use node interpreter to run commands ([#416](https://github.com/biomejs/biome-intellij/pull/416))
- Binary resolution on windows ([#556](https://github.com/biomejs/biome-intellij/pull/556))
- Binary resolution execution sequence ([#601](https://github.com/biomejs/biome-intellij/pull/601))
- Remove build range ([#1093](https://github.com/biomejs/biome-intellij/pull/1093))
- Auto-save race condition ([#26](https://github.com/biomejs/biome-intellij/pull/26))

### Documentation

- Add contribution guide ([#2](https://github.com/biomejs/biome-intellij/pull/2))

### Features

- IntelliJ Platform LSP ([#185](https://github.com/biomejs/biome-intellij/pull/185))
- Manual config path specifying ([#660](https://github.com/biomejs/biome-intellij/pull/660))
- Add onSave actions
- Improved command line execution mode (node & binary)
- Use biome check ([#28](https://github.com/biomejs/biome-intellij/pull/28))
- Validate biome.json path config ([#32](https://github.com/biomejs/biome-intellij/pull/32))

