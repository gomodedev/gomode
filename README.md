# Go Mode

[![Go Reference](https://pkg.go.dev/badge/github.com/gomodedev/gomode.svg)](https://pkg.go.dev/github.com/gomodedev/gomode)

Go Mode provides a reusable host contract and a native Android shell for
backend-hosted web applications. The repository contains the Go discovery
handler, MCP and OAuth 2.1 transports, WebRTC voice gateway, generated API
SDKs, and a SolidJS browser integration package.

Host applications own user accounts, authorization policy, product APIs,
content, and the MCP tools and resources they expose. The Android app connects
to a host through `/.well-known/gomode.json` and renders its web frontend.

User and integration guides are at [docs.gomode.dev](https://docs.gomode.dev):
[Android setup](https://docs.gomode.dev/guide/android),
[host integration](https://docs.gomode.dev/guide/host),
[voice gateway installation](https://docs.gomode.dev/guide/voice), and
[SDKs](https://docs.gomode.dev/guide/sdk).

## Packages

- Go module `github.com/gomodedev/gomode`: discovery manifest and voice token
  contracts.
  - `mcp/`, `oauth/`, `sse/`: reusable HTTP protocol implementations.
  - `httpserver/`: trusted request metadata, host checks, logging, tracing and optional GeoIP.
  - `devicelogin/`: browser pairing with explicit approval and host session issuance.
  - `voicegateway/`: standalone or embedded WebRTC voice gateway.
- `sdk/`: generated TypeScript, Kotlin, and Swift protocol clients.
- `web/src/`: `@gomodedev/gomode` SolidJS browser integration.
- `internal/gatewayfrontend/`: optional test app embedded by `cmd/voice-gateway`.
- `android/gomode/`: native WebView, voice, notifications, settings, and Halo
  shell.
- `android/halo/`: Halo smart-glasses Android SDK.

Go package documentation is on [pkg.go.dev](https://pkg.go.dev/github.com/gomodedev/gomode).
[contrib/config.toml](contrib/config.toml) documents the standalone gateway settings, and
[voicegateway/voice-choices.toml](voicegateway/voice-choices.toml) the default voice choices and stages
that the gateway and its hosts embed.

## License

Go Mode is licensed under the [GNU Affero General Public License v3](LICENSE).
For a commercial license, contact <licensing@gomode.dev>.
