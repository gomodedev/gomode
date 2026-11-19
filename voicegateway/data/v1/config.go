// Package v1 defines version 1 of the voice gateway config.toml and voice-choices.toml schemas.
//
// The gateway only reads these files; package voicegateway converts them to its
// runtime configuration and validates the result. WebRTC is also the fragment a
// host embeds in its own TOML section.
package v1

import (
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
)

// File is the standalone gateway config.toml. Voice declarations live in Choices.
type File struct {
	AutoUpdate     string          `toml:"auto_update"`
	Server         Server          `toml:"server"`
	TrustedIssuers []TrustedIssuer `toml:"trusted_issuers"`
	Debug          Debug           `toml:"debug"`
}

// Server is the config.toml [server] table.
type Server struct {
	GeoDB        string   `toml:"geo_db"`
	AllowOrigins []string `toml:"allow_origins"`
	WebRTC
	HTTP           string     `toml:"http"`
	ExternalURL    string     `toml:"external_url"`
	TrustedProxies []string   `toml:"trusted_proxies"`
	DebugFrontend  bool       `toml:"debug_frontend"`
	LogLevel       slog.Level `toml:"log_level"`
}

// WebRTC configures the gateway's WebRTC media listener and the STUN servers
// its clients use. The standalone gateway reads it from [server]; a host embeds
// it in its own TOML section, starting from voicegateway.NewWebRTCConfig so an
// omitted key keeps its default.
type WebRTC struct {
	// UDPPort is the ICE UDP mux port; 0 selects an ephemeral port.
	UDPPort int `toml:"webrtc_udp_port"`
	// STUNServers lists the stun: or stuns: URLs WebRTC clients use to learn
	// their public address. The gateway advertises them in its voice choices
	// and runs no STUN itself. Empty means clients gather host candidates only,
	// which suffices when clients can reach the gateway's advertised UDP
	// endpoint: the gateway learns each client's address from its checks.
	STUNServers []string `toml:"stun_servers"`
	// DisableUPnP skips the UPnP UDP mapping the gateway otherwise requests
	// from the LAN router at startup, along with its SSDP search of up to two
	// seconds. Disable it when clients reach the UDP port without it, or when
	// the router must not open ports on the gateway's behalf.
	DisableUPnP bool `toml:"disable_upnp"`
}

// Validate checks the port range and STUN URLs. Errors name the TOML keys
// without a section; callers prefix their section. Hosts that embed WebRTC
// call it on their own section.
func (c *WebRTC) Validate() error {
	if c.UDPPort < 0 || c.UDPPort > 65535 {
		return fmt.Errorf("webrtc_udp_port must be between 0 and 65535, got %d", c.UDPPort)
	}
	for i, raw := range c.STUNServers {
		if err := validateSTUNURL(raw); err != nil {
			return fmt.Errorf("stun_servers[%d]: %w", i, err)
		}
	}
	return nil
}

// validateSTUNURL accepts RFC 7064 stun: and stuns: URIs with an explicit host.
func validateSTUNURL(raw string) error {
	scheme, rest, ok := strings.Cut(raw, ":")
	if !ok || (scheme != "stun" && scheme != "stuns") {
		return fmt.Errorf("%q needs the stun or stuns scheme", raw)
	}
	host := rest
	if h, port, err := net.SplitHostPort(rest); err == nil {
		if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
			return fmt.Errorf("%q has an invalid port", raw)
		}
		host = h
	}
	if host == "" || strings.ContainsAny(host, "/?#@ ") {
		return fmt.Errorf("%q must name a host", raw)
	}
	return nil
}

// TrustedIssuer is one config.toml [[trusted_issuers]] entry.
type TrustedIssuer struct {
	Service   string `toml:"service"`
	Issuer    string `toml:"issuer"`
	PublicKey string `toml:"public_key"`
	OAuth     bool   `toml:"oauth"`
	Scope     string `toml:"scope"`
}

// Debug is the config.toml [debug] table.
type Debug struct {
	Trace string `toml:"trace"`
}

// Choices is voice-choices.toml, or the same tables in a host's configuration directory.
type Choices struct {
	Runtime  Runtime    `toml:"local_stack_runtime"`
	Realtime []Realtime `toml:"realtime"`
	ASR      []ASR      `toml:"asr"`
	LLM      []LLM      `toml:"llm"`
	TTS      []TTS      `toml:"tts"`
	Voice    []Voice    `toml:"voice"`
}

// Runtime is the [local_stack_runtime] table.
type Runtime struct {
	IdleTimeoutSeconds int `toml:"idle_timeout_seconds"`
	MaxInstances       int `toml:"max_instances"`
}

// ModelSettings are the model keys shared by [[realtime]] and [[llm]] entries.
type ModelSettings struct {
	LlamaCPPServer bool   `toml:"llamacppsrv"`
	Provider       string `toml:"provider"`
	Auth           string `toml:"auth"`
	Remote         string `toml:"remote"`
	Model          string `toml:"model"`
	Effort         string `toml:"effort"`
	OpenRouterSort string `toml:"openrouter_sort"`
	APIKeyName     string `toml:"api_key_name"`
}

// Realtime is one [[realtime]] entry.
type Realtime struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	ModelSettings
	Staged          bool   `toml:"staged"`
	CacheDir        string `toml:"cache_dir"`
	Threads         int    `toml:"threads"`
	Slots           int    `toml:"slots"`
	ContextSize     int    `toml:"context_size"`
	MaxOutputTokens int64  `toml:"max_output_tokens"`
}

// LLM is one [[llm]] entry.
type LLM struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	ModelSettings
	ContextSize int `toml:"context_size"`
}

// ASR is one [[asr]] entry.
type ASR struct {
	ID          string `toml:"id"`
	Label       string `toml:"label"`
	Engine      string `toml:"engine"`
	Provider    string `toml:"provider"`
	Remote      string `toml:"remote"`
	Model       string `toml:"model"`
	ContextSize int    `toml:"context_size"`
}

// TTS is one [[tts]] entry.
type TTS struct {
	ID          string `toml:"id"`
	Label       string `toml:"label"`
	Engine      string `toml:"engine"`
	Provider    string `toml:"provider"`
	Remote      string `toml:"remote"`
	Model       string `toml:"model"`
	Voice       string `toml:"voice"`
	ContextSize int    `toml:"context_size"`
}

// Voice is one [[voice]] preset entry.
type Voice struct {
	ID    string `toml:"id"`
	Label string `toml:"label"`
	ASR   string `toml:"asr"`
	LLM   string `toml:"llm"`
	TTS   string `toml:"tts"`
}
