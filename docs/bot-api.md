# Bot API Reference

> **This document has moved to the Dice Chess Bot API documentation site:**
>
> ## 👉 https://rabestro.github.io/dicechess-play-api/
>
> A navigable, searchable site for third-party bot developers — the same content, split into
> pages, plus a new English **provably-fair dice verification** guide.

The site is built from Markdown under [`docs/src/content/docs/`](./src/content/docs/) with
Astro + Starlight and deployed to GitHub Pages on every change (see
[`.github/workflows/deploy-docs.yaml`](../.github/workflows/deploy-docs.yaml)). Run it locally
with `mise run docs:dev`.

## Quick links

- **A Bot in Five Minutes** — https://rabestro.github.io/dicechess-play-api/quickstart/
- **Authentication & Identity** — https://rabestro.github.io/dicechess-play-api/authentication/
- **Game Mechanics** (DFEN, legal-move tree, time controls) — https://rabestro.github.io/dicechess-play-api/game-mechanics/
- **Connection Modes** (poll · stream · webhook) — https://rabestro.github.io/dicechess-play-api/connection-modes/
- **Provably-Fair Dice** — https://rabestro.github.io/dicechess-play-api/provably-fair/
- **REST Endpoints** — https://rabestro.github.io/dicechess-play-api/reference/rest/
- **Event Streams** — https://rabestro.github.io/dicechess-play-api/reference/streaming/
- **Webhooks** — https://rabestro.github.io/dicechess-play-api/reference/webhooks/
- **Data Shapes** — https://rabestro.github.io/dicechess-play-api/reference/data-shapes/

A minimal, dependency-free reference bot lives at [`examples/random_bot.py`](./examples/random_bot.py).
