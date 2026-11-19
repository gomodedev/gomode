// Package v1 defines version 1 of the persisted voice activity records, independent of the session protocol.
package v1

import (
	"encoding/json"
	"time"
)

// Source identifies which side of the session emitted an activity message.
type Source string

const (
	// SourceClient identifies activity from the client.
	SourceClient Source = "client"
	// SourceGateway identifies activity from the gateway.
	SourceGateway Source = "gateway"
)

// Record is one JSONL activity entry. Message retains the complete opaque protocol message.
//
// Protocol names the gateway contract of Message, such as "v2", whatever version the
// session negotiated: client messages are recorded after the published-version adapter
// normalizes them. Records without Protocol predate this field and mix client messages
// in the session's version with gateway messages in v2.
type Record struct {
	Timestamp time.Time       `json:"ts"`
	Source    Source          `json:"src"`
	Protocol  string          `json:"protocol"`
	Kind      string          `json:"kind"`
	Message   json.RawMessage `json:"msg"`
}
