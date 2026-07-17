---
title: Webhooks
description: Register one HTTPS callback and let the server POST your turns — the response body is the move. A bot becomes a single stateless function.
---

The push alternative to streams and polling (**registered bots only**): register an HTTPS callback once, and the server POSTs to it whenever it is your turn — **the HTTP response body is your move**. A bot becomes a single stateless HTTPS handler, woken only when there is a decision to make. Works with every time control — a 1–3 s cold start is noise against a Fischer 300+3 budget.

Webhooks are enabled per server by the operator; when off, every endpoint below answers `503 Service Unavailable`. The per-turn wait is bounded by both a server cap and the mover's remaining clock.

## Register a webhook

`POST /bot/webhook`

```json
{ "url": "https://my-function.example.com/dicechess" }
```

The URL must be **HTTPS** and resolve to a **public** address — loopback, RFC1918, link-local, CGNAT and IPv6-ULA targets are rejected, so the server can never be pointed at anyone's internal network. Before anything is stored, the server runs an **ownership handshake**: it POSTs `{"type":"verification","nonce":"<random>"}` to the URL, and the endpoint must answer `200` with `{"nonce":"<the same value>"}`. Only then does the webhook become active — no game data is ever sent to an unverified URL.

- **Response** `201`

  ```json
  { "url": "https://my-function.example.com/dicechess", "secret": "3f9a…64 hex chars…c2" }
  ```

  `secret` is the per-bot HMAC key the server signs every delivery with — **shown exactly once.** Keep it in your function's secret storage. Re-registering replaces both URL and secret.
- **Errors:** `403` anonymous/static caller; `422` URL-policy violation or failed handshake (the body says which); `429` per-IP registration budget; `503` webhooks disabled.

## Inspect / remove

`GET /bot/webhook` → `200 { "url": …, "verifiedAt": "2026-07-17T12:00:00Z" }` (the secret is never shown again), or `404` if none.

`DELETE /bot/webhook` → `204`; deliveries stop at the **next turn**. Mid-game included — the games themselves keep running and keep charging your clock.

## The turn delivery

When it is your turn in any of your games, the server POSTs **one** request:

```json
{
  "type": "yourTurn",
  "gameId": "game-uuid",
  "seat": "White",
  "state": { "version": 4, "dfen": "…", "activeSeat": "White", "dicePending": true, "legalMoves": { "e2e4": {} }, "clocks": { "white": 295000, "black": 300000 }, "…": "…" }
}
```

`state` is exactly the [`Snapshot.state`](../streaming/#snapshot) object — `dfen` (dice in its 7th field), `activeSeat`, `dicePending`, `clocks`, `commit`, and the inline [`legalMoves`](../../game-mechanics/#legal-moves) tree under the usual cap (fetch [`GET /games/{id}/moves`](../rest/#get-legal-moves) when it is `null`). The envelope is self-sufficient: a pure function picks its move from `legalMoves` alone.

### Verify the signature

Every delivery (the verification handshake included) carries two headers:

```text
X-DiceChess-Timestamp: 1752750000
X-DiceChess-Signature: <hex of HMAC-SHA256(secret, "<timestamp>.<raw body>")>
```

**Verify before trusting**: recompute the HMAC over `timestamp + "." + raw body` with your stored secret, compare against the signature header, and reject timestamps outside a ±5-minute window (replay guard). The one delivery you cannot check is the registration handshake — the secret is disclosed only after it succeeds.

### Respond with the move

Answer within the timeout with the same shape [`POST /bot/game/{id}/move`](../rest/#submit-turn-moves) accepts:

```json
{ "moves": ["e2e4", "g1f3"] }
```

- `200` with a legal turn → the move is played (same engine validation as the move endpoint).
- Anything else — a timeout, a non-200, a malformed body, an illegal turn, or `{"moves": []}` — plays nothing: **your clock keeps running**, and the game forfeits on time exactly as if a polling bot had stopped polling.

Delivery is **single-attempt** by design — no retries, no redelivery. The recovery budget for a transient glitch is your remaining clock, not a queue. The wait for your answer is `min(server cap, your remaining clock)`.

A webhook bot on the [rating ladder](../../authentication/#joining-the-rating-ladder) is fully passive: the scheduler starts the games and the webhook delivers the turns — the function needs no other integration. (Webhook bots do not contribute a client dice seed today; the [provably-fair scheme](../../provably-fair/) covers them with the participant-bound fallback, and the seed endpoint stays available to hybrid bots that also hold streams.)
