# Contributing

Thank you for your interest in contributing to this project!

Please review the following guidelines before making your contribution.

> [!IMPORTANT]
> If you plan on making a significant contribution, we recommend that you first create a discussion describing your
> proposed contribution to the project. This allows the project maintainers to provide early feedback that can help guide
> your contribution.

## Project setup

Ensure that you have the following tools installed on your machine:

- Java development kit (JDK) 21
- IntelliJ IDEA Ultimate Edition
- git-cliff (_maintainers only_)

1. Fork the repository and clone it to your local machine.
   ```shell
   gh repo fork biomejs/biome-intellij --clone
   ```

## Development

The plugin can be started in IDEA by running the `runIde` Gradle task. This will start a new instance of IDEA with the
plugin loaded.

```shell
./gradlew runIde
```

### Plugin regression tests

On Linux x64, install Node.js 24 and pnpm 10.5.2 through Corepack before running
the headless plugin and LSP regression tests:

```sh
corepack enable
corepack prepare pnpm@10.5.2 --activate
python3 .github/scripts/test-check-required-tests.py
python3 .github/scripts/run-required-tests.py
python3 .github/scripts/check-required-tests.py build/test-results/test
```

The runner removes old test reports and selects the classes listed in
`.github/required-tests/*.json`. Each file maps fully qualified class names to
required test method names. Give each change its own inventory file so independent
PRs can add coverage without editing a shared list. The report checker rejects
missing, skipped or failed tests and malformed reports.

The Gradle test task uses the SDK's JetBrains Runtime and a separate Biome cache
for each checkout. Remote Robot UI tests require a running IDE and remain outside
this regression command.

### Remote Robot UI tests

The Remote Robot suite needs the UI test IDE running in another process:

```shell
./gradlew runIdeForUiTests &
./gradlew test --tests com.github.biomejs.intellijbiome.BasicProjectNpmTest
```

## Making changes

1. **Create a branch.** Before making any changes, create a branch to work on.
   ```shell
   git checkout -b my-branch-name
   ```
2. **Make your changes.** Make your changes to the codebase and commit them. The format of your commit messages is not
   important at this stage because they will be squashed later, but please ensure that your commit messages are
   descriptive.

3. **Create a pull request.** Once you are done making your changes, push your branch to your fork and create a pull
   request. Please ensure that the title of your pull request follows the conventional commits specification.

## Maintainers

This section is for maintainers only. It describes the process for releasing a new version of the extension.

### Releasing a stable version

1. Create a new branch for the release.
   ```shell
   git fetch
   git checkout -b release/vX.Y.Z main
   ```
2. Generate the changelog.
   ```shell
   git-cliff --bump e71479100d4ed81b3e9c26881c38a0ddb7da31eb..
   ```
3. Bump the version in `gradle.properties` and to match the latest version in the changelog.
4. Commit and push your changes.
5. Create a pull request named `chore(release): prepare vX.Y.Z`.
6. Merge the pull request.
7. Run the [`Publish`](https://github.com/biomejs/biome-intellij/actions/workflows/publish.yaml) workflow manually from
   the Actions tab in GitHub (uncheck _nightly_).

### Releasing a nightly version

1. Commit your changes to the _main_ branch.
2. Generate the changelog.
   ```shell
   git-cliff e71479100d4ed81b3e9c26881c38a0ddb7da31eb..
   ```
3. Commit and push your changes.
4. Run the [`Publish`](https://github.com/biomejs/biome-intellij/actions/workflows/publish.yaml) workflow manually from
   the Actions tab in GitHub (check _nightly_).
