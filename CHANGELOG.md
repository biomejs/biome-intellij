# Changelog

All notable changes to this project will be documented in this file.

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

