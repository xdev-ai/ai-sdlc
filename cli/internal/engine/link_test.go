package engine

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestLinkPullRequestRetriesTransientFailureAndUsesGovernedEndpoint(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost || request.URL.Path != "/api/v1/projects/project-1/scm-events/event-1/validation-run" {
			t.Fatalf("unexpected request %s %s", request.Method, request.URL.Path)
		}
		if request.Header.Get("Authorization") != "Bearer token" {
			t.Fatal("missing bearer token")
		}
		var payload map[string]string
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil || payload["validationRunId"] != "run-1" {
			t.Fatalf("unexpected payload: %v, %v", payload, err)
		}
		if calls.Add(1) == 1 {
			writer.WriteHeader(http.StatusServiceUnavailable)
			_, _ = writer.Write([]byte("temporary"))
			return
		}
		writer.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	if err := LinkPullRequest(LinkPullRequestOptions{APIURL: server.URL, ProjectID: "project-1", EventID: "event-1", ValidationRunID: "run-1", Token: "token", MaxAttempts: 2}); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("expected two attempts, got %d", calls.Load())
	}
}

func TestLinkPullRequestRejectsMissingRequiredInputs(t *testing.T) {
	err := LinkPullRequest(LinkPullRequestOptions{APIURL: "https://example.invalid"})
	if err == nil || !strings.Contains(err.Error(), "SCM event") {
		t.Fatalf("expected required input error, got %v", err)
	}
}
