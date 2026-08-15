package engine

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
)

func TestSyncRetriesTransientFailuresWithSameIdempotencyKey(t *testing.T) {
	resultPath := filepath.Join(t.TempDir(), "result.json")
	if err := WriteResult(resultPath, Result{Status: "PASSED", CLIVersion: "test", KitVersion: "kit@1", ModelPin: "vendor/model@abc", Findings: []Finding{}, Evidence: []Evidence{}}); err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Idempotency-Key") != "stable-key" {
			t.Error("idempotency key changed across request")
		}
		if calls.Add(1) == 1 {
			writer.WriteHeader(http.StatusServiceUnavailable)
			_, _ = writer.Write([]byte("temporary"))
			return
		}
		writer.WriteHeader(http.StatusCreated)
	}))
	defer server.Close()
	if err := SyncWithOptions(SyncOptions{ResultPath: resultPath, APIURL: server.URL, ProjectID: "project", Token: "token", IdempotencyKey: "stable-key", MaxAttempts: 2}); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("expected retry, got %d requests", calls.Load())
	}
	if _, err := os.Stat(resultPath); err != nil {
		t.Fatal(err)
	}
}
