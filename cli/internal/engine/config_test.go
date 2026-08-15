package engine

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestConfigRoundTripRejectsUnknownKeys(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".aisdlc.yml")
	expected := Config{Project: "8a51508f-1fec-4e05-93c4-4a0b5d0b87bf", APIURL: "https://control.example", SpecDir: "governance", KitVersion: "core@1.0.0", ModelPin: "vendor/model@abc"}
	if err := WriteConfig(path, expected, false); err != nil {
		t.Fatal(err)
	}
	actual, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if actual != expected {
		t.Fatalf("unexpected config: %#v", actual)
	}
	if err := os.WriteFile(path, []byte("unsupported: value\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadConfig(path); err == nil {
		t.Fatal("unknown configuration keys must be rejected")
	}
}

func TestStoredTokenRequiresValidExpiry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tokens", "token.json")
	if err := SaveToken(path, StoredToken{AccessToken: "access", ExpiresAt: time.Now().Add(time.Hour)}); err != nil {
		t.Fatal(err)
	}
	stored, err := LoadToken(path)
	if err != nil || stored.AccessToken != "access" {
		t.Fatalf("unexpected stored token: %#v %v", stored, err)
	}
	if err := SaveToken(path, StoredToken{AccessToken: "access", ExpiresAt: time.Now().Add(-time.Hour)}); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadToken(path); err == nil {
		t.Fatal("expired token must be rejected")
	}
}
