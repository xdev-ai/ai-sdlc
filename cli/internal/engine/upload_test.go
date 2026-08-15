package engine

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUploadEvidenceStreamsMultipartWithDigestAndDeterministicIdempotency(t *testing.T) {
	var receivedKey string
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if got := request.Header.Get("Authorization"); got != "Bearer token-value" {
			t.Fatalf("authorization = %q", got)
		}
		if got := request.Header.Get("X-Content-SHA256"); got != "c1cda26362828b69266512052b97cb3729e3b052e4ade47c0a1e3383defe73c7" {
			t.Fatalf("digest = %q", got)
		}
		receivedKey = request.Header.Get("Idempotency-Key")
		if !strings.HasPrefix(receivedKey, "upload-") {
			t.Fatalf("idempotency key = %q", receivedKey)
		}
		if err := request.ParseMultipartForm(1024 * 1024); err != nil {
			t.Fatal(err)
		}
		if got := request.FormValue("assetType"); got != "VALIDATION" {
			t.Fatalf("asset type = %q", got)
		}
		if got := request.FormValue("accessLevel"); got != "PROJECT" {
			t.Fatalf("access level = %q", got)
		}
		file, header, err := request.FormFile("file")
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		data, err := io.ReadAll(file)
		if err != nil {
			t.Fatal(err)
		}
		if string(data) != "proof" {
			t.Fatalf("file payload = %q", data)
		}
		if !strings.HasPrefix(header.Header.Get("Content-Type"), "text/plain") {
			t.Fatalf("part content type = %q", header.Header.Get("Content-Type"))
		}
		writer.WriteHeader(http.StatusCreated)
	}))
	defer server.Close()

	directory := t.TempDir()
	filePath := filepath.Join(directory, "evidence.txt")
	if err := os.WriteFile(filePath, []byte("proof"), 0600); err != nil {
		t.Fatal(err)
	}
	result, err := UploadEvidence(UploadOptions{FilePath: filePath, APIURL: server.URL, ProjectID: "project-123", Token: "token-value", MaxAttempts: 1})
	if err != nil {
		t.Fatal(err)
	}
	if result.SHA256 != "c1cda26362828b69266512052b97cb3729e3b052e4ade47c0a1e3383defe73c7" {
		t.Fatalf("result digest = %q", result.SHA256)
	}
	if result.IdempotencyKey != receivedKey {
		t.Fatalf("result key = %q, received key = %q", result.IdempotencyKey, receivedKey)
	}
	if !strings.HasPrefix(result.ContentType, "text/plain") {
		t.Fatalf("content type = %q", result.ContentType)
	}
}
