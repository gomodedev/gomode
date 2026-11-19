// Package v1 defines version 1 of the on-disk named IP-origin cache.
package v1

import "time"

// Entry contains the origin CIDRs and their refresh timestamp.
type Entry struct {
	Updated  time.Time `json:"updated"`
	Prefixes []string  `json:"prefixes"`
}
