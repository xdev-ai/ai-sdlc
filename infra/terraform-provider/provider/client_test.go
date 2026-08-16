package provider

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestClientUsesBearerAndProjectScopedPath(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/api/v1/projects/project-1/risk-intelligence/latest" {
			t.Fatalf("unexpected path: %s", request.URL.Path)
		}
		if request.Header.Get("Authorization") != "Bearer test-token" {
			t.Fatalf("missing bearer authorization")
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"score":20}`))
	}))
	defer server.Close()
	var output struct {
		Score int `json:"score"`
	}
	instance := &client{baseURL: server.URL, token: "test-token", projectID: "project-1", http: server.Client()}
	if err := instance.request(context.Background(), "GET", "/api/v1/projects/project-1/risk-intelligence/latest", nil, &output); err != nil {
		t.Fatal(err)
	}
	if output.Score != 20 {
		t.Fatalf("unexpected score: %d", output.Score)
	}
}

func TestClientPreservesNonSuccessfulResponseForProviderDiagnostics(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		http.Error(response, "forbidden", http.StatusForbidden)
	}))
	defer server.Close()
	instance := &client{baseURL: server.URL, token: "test-token", projectID: "project-1", http: server.Client()}
	err := instance.request(context.Background(), "GET", "/denied", nil, nil)
	if err == nil || !strings.Contains(err.Error(), "HTTP 403") {
		t.Fatalf("expected HTTP 403 error, got %v", err)
	}
}
