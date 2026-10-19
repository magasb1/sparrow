package tlsutil

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
)

func TestSelfSignedIsCreatedOnceAndPinned(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "data")
	c1, err := LoadOrCreateSelfSigned(dir, []string{"192.168.1.10", "sparrow.lan"})
	if err != nil {
		t.Fatal(err)
	}
	c2, err := LoadOrCreateSelfSigned(dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	p1, _ := Pin(c1)
	p2, _ := Pin(c2)
	if p1 == "" || p1 != p2 {
		t.Fatalf("pin changed between starts: %q vs %q", p1, p2)
	}
	leaf, _ := x509.ParseCertificate(c1.Certificate[0])
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	if p1 != base64.RawURLEncoding.EncodeToString(sum[:]) {
		t.Fatal("pin is not the SPKI hash")
	}
	if err := leaf.VerifyHostname("192.168.1.10"); err != nil {
		t.Fatal(err)
	}
	if err := leaf.VerifyHostname("sparrow.lan"); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(filepath.Join(dir, "tls.key"))
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("key file mode %v", st.Mode().Perm())
	}
}
