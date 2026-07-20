# Contributing to Swagger Viewer

Thank you for your interest in contributing! This guide covers everything you need to get started.

---

## Table of Contents

- [Development Setup](#development-setup)
- [Branch Strategy](#branch-strategy)
- [Commit Message Convention](#commit-message-convention)
- [Coding Conventions](#coding-conventions)
- [Build & Test](#build--test)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Issues](#reporting-issues)

---

## Development Setup

**Requirements**
- JDK 21
- IntelliJ IDEA (Community or Ultimate) — the plugin itself must work on both
- Gradle 8.x (Gradle Wrapper included)

**Steps**
1. Fork and clone the repository
2. Open the project in IntelliJ IDEA
3. Wait for Gradle sync to complete
4. Run `./gradlew runIde` to launch a sandbox IDE and verify your changes manually

---

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Stable, release-ready code |
| `feat/<name>` | New features |
| `fix/<name>` | Bug fixes |
| `refactor/<name>` | Refactoring with no behavior change |
| `docs/<name>` | Documentation only |

Always branch off from `main`.

---

## Commit Message Convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <short summary in present tense>

[optional body]
```

**Types**: `feat` · `fix` · `refactor` · `docs` · `test` · `chore`

**Examples**
```
feat: add callback annotation support
fix: prevent NPE when @Operation summary is null
refactor: migrate async processing to coroutine-based pipeline
docs: update README with current project structure
```

- Use the imperative mood ("add", not "added" or "adds")
- Keep the first line under 72 characters
- Explain *why* in the body if the change is non-obvious

---

## Coding Conventions

### Community/Ultimate Parity — the most important rule

This plugin must work identically on **IntelliJ IDEA Community and Ultimate**.

- **Do not use Ultimate-only APIs** (e.g., `com.intellij.spring`, JavaEE tools)
- Before using any IntelliJ Platform API, verify it is bundled in Community builds via the [IntelliJ Platform SDK docs](https://plugins.jetbrains.com/docs/intellij/)
- Currently safe dependencies: `com.intellij.java`, `org.jetbrains.plugins.yaml` (both bundled in Community)
- If a feature cannot be implemented without an Ultimate-only API, open an issue to discuss alternatives before writing code

### Architecture

- **`/view`** consumes models produced by `/service` — PSI access and annotation parsing must not leak into the view layer
- **`/service`** operates purely via PSI static analysis — no runtime execution, no Spring context loading
- PSI parsing must run in a background `ReadAction`, never on the EDT

### General

- No comments that restate what the code does — only add a comment when the *why* is non-obvious
- No deprecated or `@Internal` IntelliJ APIs — `verifyPlugin` will catch these, but check beforehand
- New Extension Points must be registered in `plugin.xml` with a comment explaining why they are needed

---

## Build & Test

```bash
./gradlew test                  # Run unit tests — required to pass before opening a PR
./gradlew verifyPlugin          # Plugin Verifier — checks for internal API usage and binary compatibility
./gradlew verifyPluginStructure # Validate plugin.xml structure
./gradlew buildPlugin           # Build the plugin zip
./gradlew runIde                # Launch sandbox IDE for manual testing (GUI — run locally only)
```

All tests must pass and `verifyPlugin` must produce no errors before submitting a PR.

---

## Submitting a Pull Request

1. Make sure `./gradlew test` and `./gradlew verifyPlugin` both pass
2. Keep the PR focused — one concern per PR
3. Fill in the PR description: what changed and why
4. If your change affects the user-visible behavior, include a brief description of how you tested it manually via `runIde`

---

## Reporting Issues

When filing a bug report, please include:

- IntelliJ IDEA version and edition (Community / Ultimate)
- Plugin version
- A minimal reproduction case (ideally a small Spring controller snippet that triggers the issue)
- What you expected vs. what actually happened
