# Android voice sessions are covered on the production path

The controlled fixture drives same-origin text and WebRTC sessions in the default
model-free Android suite and CI. Extend it to cover an external gateway with
service authorization and more text conversation frames.

## Phase 1 — gateway-auth: The fixture exercises the external gateway path

- **Scope:** The fixture's settings document and text route. In external mode it
  advertises a token endpoint, returns a `ServiceAuthorization`, and rejects a
  text session whose `Authorization`, `X-Service-Kind`, `X-Service-Instance`, or
  `X-Service-Origin` header is missing or wrong.
- **Preserve:** The same-origin mode, which the current test depends on. A flag
  selects the mode; the default stays same-origin.
- **Verify:** The default model-free suite runs both modes. A run with one header
  dropped fails with the app's own error text, and the default run passes.

## Phase 2 — conversation-frames: The fixture drives a real conversation

- **Depends on:** gateway-auth
- **Scope:** The fixture's text session and an instrumented test: a second user
  message and a gateway `error` frame. Fixture tool calls already round-trip
  through `/fixture/tool` in `CarDialInterceptionTest`.
- **Verify:** Each case asserts the session's observed state, such as transcript
  entries or the error text, rather than that the app survived.
