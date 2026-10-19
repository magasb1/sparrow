package relay

import (
	"errors"
	"strings"
)

// StreamID is a parsed SRT access-control stream ID: "#!::r=cam-01,m=publish,k=secret".
type StreamID struct {
	Resource string // r: the alias
	Mode     string // m: "request" (default) or "publish"
	Key      string // k: publish key
}

// ParseStreamID parses the SRT access-control syntax. Unknown keys are ignored.
func ParseStreamID(s string) (StreamID, error) {
	const prefix = "#!::"
	if !strings.HasPrefix(s, prefix) {
		return StreamID{}, errors.New("stream id must start with #!::")
	}
	id := StreamID{Mode: "request"}
	for _, kv := range strings.Split(s[len(prefix):], ",") {
		k, v, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		switch k {
		case "r":
			id.Resource = v
		case "m":
			id.Mode = v
		case "k":
			id.Key = v
		}
	}
	if !ValidAlias(id.Resource) {
		return StreamID{}, errors.New("missing or invalid resource")
	}
	return id, nil
}

// ValidAlias accepts 1–64 characters of a–z, 0–9 and '-'.
func ValidAlias(a string) bool {
	if len(a) == 0 || len(a) > 64 {
		return false
	}
	for _, c := range a {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
			return false
		}
	}
	return true
}
