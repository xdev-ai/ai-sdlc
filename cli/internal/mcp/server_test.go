package mcp

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// newTestServer wires the server against a stub API so the protocol can be exercised without a control plane.
func newTestServer(t *testing.T, handler http.HandlerFunc) (*Server, *httptest.Server) {
	t.Helper()
	api := httptest.NewServer(handler)
	t.Cleanup(api.Close)
	server, err := New(Options{APIBaseURL: api.URL, Token: "test-token", ProjectID: "project-1", Version: "1.2.3"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return server, api
}

// exchange feeds newline-delimited requests through Serve and returns the decoded replies.
func exchange(t *testing.T, server *Server, lines ...string) []map[string]any {
	t.Helper()
	var out strings.Builder
	if err := server.Serve(strings.NewReader(strings.Join(lines, "\n")+"\n"), &out); err != nil {
		t.Fatalf("Serve: %v", err)
	}
	var replies []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		var reply map[string]any
		if err := json.Unmarshal([]byte(line), &reply); err != nil {
			t.Fatalf("reply is not JSON: %q: %v", line, err)
		}
		replies = append(replies, reply)
	}
	return replies
}

func toolTextOf(t *testing.T, reply map[string]any) string {
	t.Helper()
	result, ok := reply["result"].(map[string]any)
	if !ok {
		t.Fatalf("reply carried no result: %v", reply)
	}
	content, ok := result["content"].([]any)
	if !ok || len(content) == 0 {
		t.Fatalf("result carried no content: %v", result)
	}
	first, _ := content[0].(map[string]any)
	text, _ := first["text"].(string)
	return text
}

func TestInitializeReportsTheProtocolAndServerIdentity(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})

	replies := exchange(t, server, `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`)

	if len(replies) != 1 {
		t.Fatalf("expected one reply, got %d", len(replies))
	}
	result := replies[0]["result"].(map[string]any)
	if result["protocolVersion"] != ProtocolVersion {
		t.Errorf("protocolVersion = %v, want %s", result["protocolVersion"], ProtocolVersion)
	}
	info := result["serverInfo"].(map[string]any)
	if info["name"] != "aisdlc" || info["version"] != "1.2.3" {
		t.Errorf("serverInfo = %v", info)
	}
	if _, ok := result["capabilities"].(map[string]any)["tools"]; !ok {
		t.Error("a tools-only server must still advertise the tools capability")
	}
}

// A notification has no id and must not be answered. Some clients treat a reply to one as fatal, which would present
// as the server dying immediately after a successful handshake.
func TestANotificationIsNotAnswered(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`,
		`{"jsonrpc":"2.0","id":7,"method":"ping"}`)

	if len(replies) != 1 {
		t.Fatalf("expected exactly one reply (the ping), got %d: %v", len(replies), replies)
	}
	if replies[0]["id"].(float64) != 7 {
		t.Errorf("the reply belongs to the wrong request: %v", replies[0])
	}
}

func TestMalformedInputIsReportedWithoutKillingTheServer(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})

	replies := exchange(t, server, `{not json`, `{"jsonrpc":"2.0","id":2,"method":"ping"}`)

	if len(replies) != 2 {
		t.Fatalf("expected a parse error and then a working reply, got %d: %v", len(replies), replies)
	}
	rpcErr, ok := replies[0]["error"].(map[string]any)
	if !ok || rpcErr["code"].(float64) != -32700 {
		t.Errorf("first reply should be a JSON-RPC parse error, got %v", replies[0])
	}
	if _, ok := replies[1]["result"]; !ok {
		t.Error("the server must keep serving after a malformed line")
	}
}

func TestToolsListAdvertisesEveryToolWithASchema(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})

	replies := exchange(t, server, `{"jsonrpc":"2.0","id":3,"method":"tools/list"}`)

	tools := replies[0]["result"].(map[string]any)["tools"].([]any)
	names := map[string]bool{}
	for _, entry := range tools {
		tool := entry.(map[string]any)
		name := tool["name"].(string)
		names[name] = true
		if strings.TrimSpace(tool["description"].(string)) == "" {
			t.Errorf("%s has no description; a model chooses tools by their description", name)
		}
		if _, ok := tool["inputSchema"].(map[string]any); !ok {
			t.Errorf("%s has no inputSchema", name)
		}
	}
	for _, expected := range []string{"aisdlc_get_rules", "aisdlc_search_docs", "aisdlc_get_context", "aisdlc_read_page"} {
		if !names[expected] {
			t.Errorf("%s is not advertised", expected)
		}
	}
}

func TestAnUnknownMethodIsAJsonRpcErrorAndAnUnknownToolIsNot(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":4,"method":"resources/list"}`,
		`{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope","arguments":{}}}`)

	if _, ok := replies[0]["error"]; !ok {
		t.Error("an unimplemented protocol method must be a JSON-RPC error")
	}
	// A tool failure has to arrive as tool content, or the model never sees the message and cannot correct itself.
	result, ok := replies[1]["result"].(map[string]any)
	if !ok {
		t.Fatalf("a bad tool name must not become a transport error: %v", replies[1])
	}
	if result["isError"] != true {
		t.Errorf("the tool result must be flagged as an error: %v", result)
	}
}

func TestGetRulesReturnsTheServerComposedMarkdown(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/projects/project-1/agent-rules/markdown" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("the token was not sent: %q", r.Header.Get("Authorization"))
		}
		_, _ = w.Write([]byte("# Governing rules\n\n- invariant one\n"))
	})

	replies := exchange(t, server, `{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"aisdlc_get_rules","arguments":{}}}`)

	text := toolTextOf(t, replies[0])
	if !strings.Contains(text, "# Governing rules") || !strings.Contains(text, "invariant one") {
		t.Errorf("the rules text was not passed through: %q", text)
	}
}

// The organization is not configured anywhere on a developer machine, so it is resolved from the project once and
// remembered. Without this, every knowledge tool needs a value the user cannot derive.
func TestTheOrganizationIsResolvedFromTheProjectAndCached(t *testing.T) {
	rulesCalls := 0
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/projects/project-1/agent-rules":
			rulesCalls++
			_, _ = w.Write([]byte(`{"organizationId":"org-9"}`))
		case strings.HasPrefix(r.URL.Path, "/api/v1/organizations/org-9/knowledge/search"):
			_, _ = w.Write([]byte(`[]`))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"aisdlc_search_docs","arguments":{"query":"a"}}}`,
		`{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"aisdlc_search_docs","arguments":{"query":"b"}}}`)

	if len(replies) != 2 {
		t.Fatalf("expected two replies, got %d", len(replies))
	}
	if rulesCalls != 1 {
		t.Errorf("the organization should be resolved once and cached, resolved %d times", rulesCalls)
	}
}

func TestSearchSendsTheQueryAndRendersCitableHits(t *testing.T) {
	var captured string
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/agent-rules") {
			_, _ = w.Write([]byte(`{"organizationId":"org-9"}`))
			return
		}
		captured = r.URL.RawQuery
		_, _ = w.Write([]byte(`[{"spaceKey":"DOCS","slug":"tiep-nhan","version":2,"ordinal":1,
			"headingPath":"Tiếp nhận > Kiểm tra bảo hiểm","content":"Xác minh thẻ.","matchedBy":"keyword",
			"pageId":"page-1","score":0.1}]`))
	})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"aisdlc_search_docs","arguments":{"query":"bao hiem","spaceKey":"DOCS","limit":5}}}`)

	if !strings.Contains(captured, "q=bao+hiem") && !strings.Contains(captured, "q=bao%20hiem") {
		t.Errorf("the query was not sent: %q", captured)
	}
	if !strings.Contains(captured, "spaceKey=DOCS") || !strings.Contains(captured, "limit=5") {
		t.Errorf("filters were not sent: %q", captured)
	}
	text := toolTextOf(t, replies[0])
	for _, expected := range []string{"DOCS/tiep-nhan v2", "Kiểm tra bảo hiểm", "keyword", "pageId page-1", "Xác minh thẻ."} {
		if !strings.Contains(text, expected) {
			t.Errorf("rendered hit is missing %q:\n%s", expected, text)
		}
	}
}

// The distinction the whole retrieval design rests on: nothing matched is not the same as nothing exists. An agent
// that conflates them will state that a requirement is undocumented.
func TestAnEmptySearchTellsTheModelWhatEmptyMeans(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/agent-rules") {
			_, _ = w.Write([]byte(`{"organizationId":"org-9"}`))
			return
		}
		_, _ = w.Write([]byte(`[]`))
	})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"aisdlc_search_docs","arguments":{"query":"zzz"}}}`)

	text := toolTextOf(t, replies[0])
	if !strings.Contains(text, "keyword matching, not semantic") {
		t.Errorf("the empty result must explain that matching is lexical:\n%s", text)
	}
	if !strings.Contains(text, "not evidence that the documentation omits") {
		t.Errorf("the empty result must not read as an absence of documentation:\n%s", text)
	}
}

func TestContextCarriesEveryCitationAndTheCaveat(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/agent-rules") {
			_, _ = w.Write([]byte(`{"organizationId":"org-9"}`))
			return
		}
		if !strings.Contains(r.URL.RawQuery, "budgetChars=1234") {
			t.Errorf("the budget was not forwarded: %q", r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"query":"bao hiem","strategy":"lexical-keyword","budgetChars":1234,"usedChars":42,
			"consideredChunks":3,"citations":["DOCS/tiep-nhan v2 § A"],"caveat":"Retrieval is lexical...",
			"chunks":[{"headingPath":"A","content":"text","spaceKey":"DOCS","slug":"tiep-nhan","version":2}]}`))
	})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"aisdlc_get_context","arguments":{"question":"bao hiem","budgetChars":1234}}}`)

	text := toolTextOf(t, replies[0])
	for _, expected := range []string{"lexical-keyword", "42 of 1234 characters", "Retrieval is lexical", "DOCS/tiep-nhan v2 § A"} {
		if !strings.Contains(text, expected) {
			t.Errorf("bundle rendering is missing %q:\n%s", expected, text)
		}
	}
}

func TestAnEmptyContextBundleForbidsAnsweringFromMemory(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/agent-rules") {
			_, _ = w.Write([]byte(`{"organizationId":"org-9"}`))
			return
		}
		_, _ = w.Write([]byte(`{"query":"x","strategy":"none","budgetChars":8000,"usedChars":0,"consideredChunks":0,
			"citations":[],"caveat":"Retrieval is lexical...","chunks":[]}`))
	})

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"aisdlc_get_context","arguments":{"question":"x"}}}`)

	if !strings.Contains(toolTextOf(t, replies[0]), "Do not answer from memory") {
		t.Errorf("an empty bundle must say so plainly:\n%s", toolTextOf(t, replies[0]))
	}
}

func TestAnExpiredTokenSaysHowToFixItRatherThanReturningNothing(t *testing.T) {
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	})

	replies := exchange(t, server, `{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"aisdlc_get_rules","arguments":{}}}`)

	result := replies[0]["result"].(map[string]any)
	if result["isError"] != true {
		t.Fatalf("a 401 must be reported as a tool error: %v", result)
	}
	if !strings.Contains(toolTextOf(t, replies[0]), "aisdlc login") {
		t.Errorf("the message must name the fix:\n%s", toolTextOf(t, replies[0]))
	}
}

func TestAMissingTokenIsRefusedAtStartupWithAnActionableMessage(t *testing.T) {
	_, err := New(Options{APIBaseURL: "http://example.invalid", Token: "  "})
	if err == nil {
		t.Fatal("a server with no credential must not start")
	}
	if !strings.Contains(err.Error(), "aisdlc login") {
		t.Errorf("the refusal must name the fix, got %q", err)
	}
}

func TestAMissingApiUrlIsRefused(t *testing.T) {
	if _, err := New(Options{Token: "t"}); err == nil {
		t.Fatal("a server with no API URL must not start")
	}
}

func TestRequiredArgumentsAreCheckedBeforeAnyRequest(t *testing.T) {
	called := false
	server, _ := newTestServer(t, func(w http.ResponseWriter, r *http.Request) { called = true })

	replies := exchange(t, server,
		`{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"aisdlc_search_docs","arguments":{}}}`,
		`{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"aisdlc_read_page","arguments":{}}}`)

	for _, reply := range replies {
		if reply["result"].(map[string]any)["isError"] != true {
			t.Errorf("a missing required argument must be an error: %v", reply)
		}
	}
	if called {
		t.Error("no HTTP request should be made when a required argument is absent")
	}
}
