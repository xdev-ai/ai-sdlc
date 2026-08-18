// Package mcp exposes the AI-SDLC knowledge base and governing rules to any AI assistant that speaks the Model
// Context Protocol, over stdio.
//
// Why this lives in the existing CLI binary. A developer already installs `aisdlc` to validate and sync, it already
// authenticates and stores a token, and MCP servers are launched as a subprocess by the assistant. Shipping a second
// binary — or a Node package — would mean a second install, a second login and a second place for the token to leak.
// One binary, one credential path, one thing to update.
//
// Why the protocol is implemented by hand. This module has no external dependencies and no go.sum, which is a
// property worth keeping for something that runs on every developer machine and holds a bearer token: the supply
// chain is the Go standard library. MCP is JSON-RPC 2.0 over newline-delimited stdio, and the subset a
// tools-only server needs is small enough that a dependency would cost more than it saves.
package mcp

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ProtocolVersion is the MCP revision this server implements. It is reported during initialize; a client asking for
// something else still gets this, because claiming to speak a version we do not implement would be worse.
const ProtocolVersion = "2024-11-05"

const serverName = "aisdlc"

// Options configures one server run. Everything is injected so the transport and the API can be tested without a
// network or a subprocess.
type Options struct {
	APIBaseURL string
	Token      string
	ProjectID  string
	// OrganizationID scopes documentation search. Knowledge spaces belong to an organization, not a project.
	OrganizationID string
	Client         *http.Client
	Version        string
}

// Server speaks JSON-RPC 2.0 over a reader/writer pair.
type Server struct {
	options Options
}

func New(options Options) (*Server, error) {
	if strings.TrimSpace(options.APIBaseURL) == "" {
		return nil, errors.New("an API base URL is required")
	}
	if strings.TrimSpace(options.Token) == "" {
		// Fail closed and say exactly how to fix it. An MCP server that starts without a credential surfaces as
		// mysterious empty tool results inside someone's editor, with no clue that a login is missing.
		return nil, errors.New("no access token: run `aisdlc login` first, or set AISDLC_ACCESS_TOKEN")
	}
	if options.Client == nil {
		options.Client = &http.Client{Timeout: 20 * time.Second}
	}
	if strings.TrimSpace(options.Version) == "" {
		options.Version = "0.0.0"
	}
	return &Server{options: options}, nil
}

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type response struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

// Serve reads requests until the input ends. A malformed line is answered with a parse error rather than killing the
// server: the assistant on the other side has no way to restart it mid-conversation.
func (s *Server) Serve(in io.Reader, out io.Writer) error {
	reader := bufio.NewReader(in)
	encoder := json.NewEncoder(out)
	for {
		line, err := reader.ReadBytes('\n')
		if len(strings.TrimSpace(string(line))) > 0 {
			var incoming request
			if decodeErr := json.Unmarshal(line, &incoming); decodeErr != nil {
				if writeErr := encoder.Encode(response{
					JSONRPC: "2.0",
					Error:   &rpcError{Code: -32700, Message: "parse error: " + decodeErr.Error()},
				}); writeErr != nil {
					return writeErr
				}
			} else if reply, respond := s.handle(incoming); respond {
				if writeErr := encoder.Encode(reply); writeErr != nil {
					return writeErr
				}
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
	}
}

// handle returns the reply and whether one should be sent. Notifications — a request with no id, such as
// "notifications/initialized" — must not be answered at all; replying to one is a protocol violation that some
// clients treat as a fatal error.
func (s *Server) handle(incoming request) (response, bool) {
	if len(incoming.ID) == 0 {
		return response{}, false
	}
	reply := response{JSONRPC: "2.0", ID: incoming.ID}
	switch incoming.Method {
	case "initialize":
		reply.Result = map[string]any{
			"protocolVersion": ProtocolVersion,
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": serverName, "version": s.options.Version},
		}
	case "tools/list":
		reply.Result = map[string]any{"tools": toolDefinitions()}
	case "tools/call":
		reply.Result = s.callTool(incoming.Params)
	case "ping":
		reply.Result = map[string]any{}
	default:
		reply.Error = &rpcError{Code: -32601, Message: "method not found: " + incoming.Method}
	}
	return reply, true
}

func toolDefinitions() []map[string]any {
	return []map[string]any{
		{
			"name": "aisdlc_get_rules",
			"description": "The governing rules for this project: the active constitution, active policies, pinned " +
				"Spec Kit versions, available documentation, and the platform invariants an agent must respect. " +
				"Read this before proposing changes. Returns Markdown composed by the server.",
			"inputSchema": map[string]any{"type": "object", "properties": map[string]any{}},
		},
		{
			"name": "aisdlc_search_docs",
			"description": "Search project documentation and return the matching sections with the heading path that " +
				"cites each one. Matching is lexical and accent-insensitive for Vietnamese: 'tiep nhan' finds 'tiếp " +
				"nhận'. It is NOT semantic — an empty result means no wording matched, not that the documentation " +
				"does not cover the subject.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"query":    map[string]any{"type": "string", "description": "Words to look for."},
					"spaceKey": map[string]any{"type": "string", "description": "Restrict to one documentation space."},
					"limit":    map[string]any{"type": "integer", "description": "Maximum sections, 1-20.", "default": 10},
				},
				"required": []string{"query"},
			},
		},
		{
			"name": "aisdlc_get_context",
			"description": "Assemble a prompt-sized bundle of documentation for a question, with a citation for every " +
				"section included and a stated character budget. Use this when you need grounding rather than a list " +
				"of hits.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"question":    map[string]any{"type": "string"},
					"spaceKey":    map[string]any{"type": "string"},
					"budgetChars": map[string]any{"type": "integer", "description": "Character budget, default 8000."},
				},
				"required": []string{"question"},
			},
		},
		{
			"name":        "aisdlc_read_page",
			"description": "Read one documentation page in full, at its current version, with its version history size.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"pageId": map[string]any{"type": "string", "description": "Page id, as returned by a search hit."},
				},
				"required": []string{"pageId"},
			},
		},
	}
}

type toolCall struct {
	Name      string         `json:"name"`
	Arguments map[string]any `json:"arguments"`
}

// callTool always returns a result, never a JSON-RPC error. A tool failure is reported as tool content with
// isError set, which is what lets the model read the message and correct itself instead of the client treating the
// whole call as a transport fault.
func (s *Server) callTool(params json.RawMessage) map[string]any {
	var call toolCall
	if err := json.Unmarshal(params, &call); err != nil {
		return toolError("could not read the tool call: " + err.Error())
	}
	switch call.Name {
	case "aisdlc_get_rules":
		if s.options.ProjectID == "" {
			return toolError("no project configured: pass --project or set AISDLC_PROJECT")
		}
		body, err := s.get("/api/v1/projects/" + s.options.ProjectID + "/agent-rules/markdown")
		if err != nil {
			return toolError(err.Error())
		}
		return toolText(string(body))
	case "aisdlc_search_docs":
		query := stringArg(call.Arguments, "query")
		if query == "" {
			return toolError("query is required")
		}
		organization, orgErr := s.organization()
		if orgErr != nil {
			return toolError(orgErr.Error())
		}
		limit := intArg(call.Arguments, "limit", 10)
		if limit < 1 || limit > 20 {
			limit = 10
		}
		path := fmt.Sprintf("/api/v1/organizations/%s/knowledge/search?limit=%d&q=%s",
			organization, limit, url.QueryEscape(query))
		if space := stringArg(call.Arguments, "spaceKey"); space != "" {
			path += "&spaceKey=" + url.QueryEscape(space)
		}
		body, err := s.get(path)
		if err != nil {
			return toolError(err.Error())
		}
		return toolText(formatHits(body))
	case "aisdlc_get_context":
		question := stringArg(call.Arguments, "question")
		if question == "" {
			return toolError("question is required")
		}
		organization, orgErr := s.organization()
		if orgErr != nil {
			return toolError(orgErr.Error())
		}
		path := fmt.Sprintf("/api/v1/organizations/%s/knowledge/context?q=%s",
			organization, url.QueryEscape(question))
		if budget := intArg(call.Arguments, "budgetChars", 0); budget > 0 {
			path += fmt.Sprintf("&budgetChars=%d", budget)
		}
		if space := stringArg(call.Arguments, "spaceKey"); space != "" {
			path += "&spaceKey=" + url.QueryEscape(space)
		}
		body, err := s.get(path)
		if err != nil {
			return toolError(err.Error())
		}
		return toolText(formatBundle(body))
	case "aisdlc_read_page":
		pageID := stringArg(call.Arguments, "pageId")
		if pageID == "" {
			return toolError("pageId is required")
		}
		organization, orgErr := s.organization()
		if orgErr != nil {
			return toolError(orgErr.Error())
		}
		body, err := s.get("/api/v1/organizations/" + organization + "/knowledge/pages/" + url.PathEscape(pageID))
		if err != nil {
			return toolError(err.Error())
		}
		return toolText(formatPage(body))
	default:
		return toolError("unknown tool: " + call.Name)
	}
}

// organization resolves the organization that owns the configured project, once, and remembers it.
//
// Documentation endpoints are organization-scoped while a developer's config only names a project, so without this
// every machine would need a second value in its MCP configuration — and a value nobody can derive is a value people
// paste wrongly. The rules endpoint already knows the answer and returns it.
//
// No lock is needed: the stdio transport reads and dispatches one request at a time.
func (s *Server) organization() (string, error) {
	if s.options.OrganizationID != "" {
		return s.options.OrganizationID, nil
	}
	if s.options.ProjectID == "" {
		return "", errors.New("no project or organization configured: pass --project (or --org), " +
			"or set AISDLC_PROJECT")
	}
	body, err := s.get("/api/v1/projects/" + s.options.ProjectID + "/agent-rules")
	if err != nil {
		return "", err
	}
	var rules struct {
		OrganizationID string `json:"organizationId"`
	}
	if decodeErr := json.Unmarshal(body, &rules); decodeErr != nil {
		return "", fmt.Errorf("could not read the organization from the rules bundle: %w", decodeErr)
	}
	if strings.TrimSpace(rules.OrganizationID) == "" {
		return "", errors.New("the rules bundle carried no organization id; pass --org explicitly")
	}
	s.options.OrganizationID = rules.OrganizationID
	return rules.OrganizationID, nil
}

func (s *Server) get(path string) ([]byte, error) {
	endpoint := strings.TrimRight(s.options.APIBaseURL, "/") + path
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+s.options.Token)
	req.Header.Set("Accept", "application/json, text/plain")
	res, err := s.options.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("could not reach the AI-SDLC API at %s: %w", s.options.APIBaseURL, err)
	}
	defer res.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if readErr != nil {
		return nil, readErr
	}
	switch {
	case res.StatusCode == http.StatusUnauthorized:
		return nil, errors.New("the API rejected the token (401): run `aisdlc login` again")
	case res.StatusCode == http.StatusForbidden:
		return nil, errors.New("the API refused this scope (403): the account may lack project membership")
	case res.StatusCode >= 400:
		return nil, fmt.Errorf("the API returned %d: %s", res.StatusCode, strings.TrimSpace(truncate(string(body), 400)))
	}
	return body, nil
}

// formatHits renders search results as text a model can read directly, with the citation on every section. Handing
// back raw JSON would make the model spend tokens parsing and invite it to quote fields that are not citations.
func formatHits(body []byte) string {
	var hits []struct {
		SpaceKey    string  `json:"spaceKey"`
		Slug        string  `json:"slug"`
		Version     int     `json:"version"`
		HeadingPath string  `json:"headingPath"`
		Content     string  `json:"content"`
		MatchedBy   string  `json:"matchedBy"`
		PageID      string  `json:"pageId"`
		Score       float64 `json:"score"`
	}
	if err := json.Unmarshal(body, &hits); err != nil {
		return string(body)
	}
	if len(hits) == 0 {
		return "No section matched. This is keyword matching, not semantic search: no wording matched, which is not " +
			"evidence that the documentation omits the subject. Try fewer or different words."
	}
	var text strings.Builder
	fmt.Fprintf(&text, "%d matching section(s).\n", len(hits))
	for _, hit := range hits {
		fmt.Fprintf(&text, "\n--- %s/%s v%d § %s  [%s, pageId %s]\n%s\n",
			hit.SpaceKey, hit.Slug, hit.Version, hit.HeadingPath, hit.MatchedBy, hit.PageID, strings.TrimSpace(hit.Content))
	}
	return text.String()
}

func formatBundle(body []byte) string {
	var bundle struct {
		Query            string   `json:"query"`
		Strategy         string   `json:"strategy"`
		BudgetChars      int      `json:"budgetChars"`
		UsedChars        int      `json:"usedChars"`
		ConsideredChunks int      `json:"consideredChunks"`
		Citations        []string `json:"citations"`
		Caveat           string   `json:"caveat"`
		Chunks           []struct {
			HeadingPath string `json:"headingPath"`
			Content     string `json:"content"`
			SpaceKey    string `json:"spaceKey"`
			Slug        string `json:"slug"`
			Version     int    `json:"version"`
		} `json:"chunks"`
	}
	if err := json.Unmarshal(body, &bundle); err != nil {
		return string(body)
	}
	var text strings.Builder
	fmt.Fprintf(&text, "Context for %q — strategy %s, %d of %d characters, %d candidates considered.\n",
		bundle.Query, bundle.Strategy, bundle.UsedChars, bundle.BudgetChars, bundle.ConsideredChunks)
	fmt.Fprintf(&text, "%s\n", bundle.Caveat)
	if len(bundle.Chunks) == 0 {
		text.WriteString("\nNothing matched, so nothing is included. Do not answer from memory as though it were " +
			"grounded in this project's documentation.\n")
		return text.String()
	}
	for _, chunk := range bundle.Chunks {
		fmt.Fprintf(&text, "\n--- %s/%s v%d § %s\n%s\n",
			chunk.SpaceKey, chunk.Slug, chunk.Version, chunk.HeadingPath, strings.TrimSpace(chunk.Content))
	}
	return text.String()
}

func formatPage(body []byte) string {
	var page struct {
		SpaceKey   string   `json:"spaceKey"`
		Slug       string   `json:"slug"`
		Title      string   `json:"title"`
		Body       string   `json:"body"`
		Version    int      `json:"version"`
		PageStatus string   `json:"pageStatus"`
		Breadcrumb []string `json:"breadcrumb"`
		Labels     []string `json:"labels"`
		AuthoredBy string   `json:"authoredBy"`
		ChangeNote string   `json:"changeNote"`
		ChunkCount int      `json:"chunkCount"`
	}
	if err := json.Unmarshal(body, &page); err != nil {
		return string(body)
	}
	var text strings.Builder
	fmt.Fprintf(&text, "# %s\n\nCite as: %s/%s v%d § <section>\n", page.Title, page.SpaceKey, page.Slug, page.Version)
	fmt.Fprintf(&text, "Status %s · authored by %s · %d retrievable sections\n", page.PageStatus, page.AuthoredBy, page.ChunkCount)
	if len(page.Breadcrumb) > 0 {
		fmt.Fprintf(&text, "Path: %s\n", strings.Join(page.Breadcrumb, " / "))
	}
	if len(page.Labels) > 0 {
		fmt.Fprintf(&text, "Labels: %s\n", strings.Join(page.Labels, ", "))
	}
	if strings.TrimSpace(page.ChangeNote) != "" {
		fmt.Fprintf(&text, "Reason for the current version: %s\n", page.ChangeNote)
	}
	fmt.Fprintf(&text, "\n%s\n", page.Body)
	return text.String()
}

func toolText(text string) map[string]any {
	return map[string]any{"content": []map[string]any{{"type": "text", "text": text}}}
}

func toolError(message string) map[string]any {
	return map[string]any{
		"isError": true,
		"content": []map[string]any{{"type": "text", "text": message}},
	}
}

func stringArg(arguments map[string]any, key string) string {
	if value, ok := arguments[key].(string); ok {
		return strings.TrimSpace(value)
	}
	return ""
}

// intArg accepts a float because JSON numbers decode to float64, which is the shape every MCP client actually sends.
func intArg(arguments map[string]any, key string, fallback int) int {
	switch value := arguments[key].(type) {
	case float64:
		return int(value)
	case int:
		return value
	}
	return fallback
}

func truncate(text string, limit int) string {
	if len(text) <= limit {
		return text
	}
	return text[:limit] + "…"
}
