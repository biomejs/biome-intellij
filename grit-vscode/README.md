# grit-vscode

This directory contains some files extracted from the GritQL extension for VS Code.

## How to update

```shell
curl -sSL https://marketplace.visualstudio.com/_apis/public/gallery/publishers/Grit/vsextensions/grit-vscode/0.3.10/vspackage \
  | tar -xzf - 'extension/*.json' 'extension/dist/*.json' 'extension/syntaxes/*.json'
```
