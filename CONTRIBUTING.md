# Contributing

Thanks for helping build the Dice Chess play server. This repo follows the same conventions
as `dicechess-engine-scala` and `dicechess-analytics`.

## Prerequisites

- [mise](https://mise.jdx.dev) manages the local toolchain (Java 25, scalafmt, lefthook, …).
- SBT is the build tool; the dice-chess **engine** is resolved from GitHub Packages, which
  requires a GitHub token even for public packages. The build reads it from the `gh` CLI
  automatically (`gh auth login` once), or from `GITHUB_TOKEN` in CI.

## Setup

```bash
mise run setup      # install tools + register Git hooks (lefthook)
mise run check      # full validation: scalafmt + clean + coverage + tests (mirrors CI)
```

Other tasks: `mise run compile`, `mise run test`, `mise run format`, `mise run coverage`.

## Workflow

- **Branch naming** (enforced by CI): `(task|feat|bug)/<issue-id>-<short-desc>`,
  e.g. `feat/12-game-room`.
- **Link an issue**: the PR description must contain `Closes #<id>` (or `Fixes` / `Resolves`).
- **Formatting**: `scalafmt` runs in the pre-commit hook; CI fails on unformatted code.
- **Secrets**: the pre-commit hook scans staged diffs (betterleaks) — never commit tokens.
- **English** for code, commits, PR descriptions, and review comments.
- After opening a PR, request a review with an `@coderabbitai review` comment.

## Definition of Done

- Code compiles and tests pass: `mise run check`.
- New behaviour is covered by tests.
- Docs updated where relevant.
- CI is green and review feedback is addressed.
