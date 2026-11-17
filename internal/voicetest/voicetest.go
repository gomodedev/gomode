// Package voicetest replays a voice gateway text session recorded from the real gateway.
//
// testdata/text_session.json holds the recording.
// voicegateway/voicertc's TestServeTextSessionTrace drives the production text
// session with its client messages and fails when the gateway replies differ.
// Regenerate it with:
//
//	go test ./voicegateway/voicertc -run TestServeTextSessionTrace -update
//
// The web voice tests replay the file, and the Android voice fixture serves its
// gateway replies, so client tests cannot assume messages the gateway does not
// send.
package voicetest

import (
	_ "embed"
	"encoding/json"
	"fmt"

	voicev2 "github.com/gomodedev/gomode/voicegateway/api/v2"
)

// Sender identifies which side of a text session sent a recorded message.
type Sender string

const (
	// Client marks a message the client sends to the gateway.
	Client Sender = "client"
	// Gateway marks a message the gateway sends to the client.
	Gateway Sender = "gateway"
)

// Step is one recorded text-session message.
type Step struct {
	// From is the side that sent Message.
	From Sender `json:"from"`
	// Message is the protocol message as sent on the WebSocket.
	Message json.RawMessage `json:"message"`
	// Kind is Message's kind; TextSession sets it.
	Kind voicev2.MessageKind `json:"-"`
}

// TextSession returns the recorded text session in wire order.
func TextSession() []Step {
	var steps []Step
	if err := json.Unmarshal(textSession, &steps); err != nil {
		panic("voicetest: decode testdata/text_session.json: " + err.Error())
	}
	for i := range steps {
		var env voicev2.MessageEnvelope
		if err := json.Unmarshal(steps[i].Message, &env); err != nil {
			panic(fmt.Sprintf("voicetest: step %d: %v", i, err))
		}
		if env.Kind == "" || (steps[i].From != Client && steps[i].From != Gateway) {
			panic(fmt.Sprintf("voicetest: step %d: want a message kind from client or gateway", i))
		}
		steps[i].Kind = env.Kind
	}
	return steps
}

// GatewayReplies returns the gateway messages recorded after the first client
// message of kind, up to the next client message. It returns nil when no
// client message has that kind.
func GatewayReplies(kind voicev2.MessageKind) []json.RawMessage {
	steps := TextSession()
	for i, s := range steps {
		if s.From != Client || s.Kind != kind {
			continue
		}
		var replies []json.RawMessage
		for _, r := range steps[i+1:] {
			if r.From == Client {
				break
			}
			replies = append(replies, r.Message)
		}
		return replies
	}
	return nil
}

//go:embed testdata/text_session.json
var textSession []byte
