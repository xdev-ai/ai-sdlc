package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/xdev-ai/ai-sdlc/cli/internal/engine"
)

const version = "0.1.0"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "init":
		initConfig(os.Args[2:])
	case "login":
		login(os.Args[2:])
	case "validate":
		validate(os.Args[2:])
	case "sync":
		sync(os.Args[2:])
	case "status":
		status(os.Args[2:])
	case "version", "--version", "-v":
		fmt.Println(version)
	default:
		usage()
		os.Exit(2)
	}
}

func validate(args []string) {
	fs := flag.NewFlagSet("validate", flag.ExitOnError)
	configPath := fs.String("config", engine.DefaultConfigPath, "AI-SDLC configuration path")
	config, err := engine.LoadConfigIfPresent(*configPath)
	if err != nil {
		fatal(err)
	}
	specDir := fs.String("spec-dir", config.SpecDir, "Spec Kit directory")
	kitVersion := fs.String("kit-version", config.KitVersion, "Resolved Spec Kit version")
	modelPin := fs.String("model", config.ModelPin, "Pinned AI model reference, e.g. provider/model@revision")
	bare := fs.Bool("bare", false, "Forbidden: bypass governed context")
	out := fs.String("out", "validation-result.json", "Validation evidence output")
	format := fs.String("format", "json", "Output format: json, junit, or sarif")
	_ = fs.Parse(args)

	result, err := engine.Validate(engine.ValidateOptions{SpecDir: *specDir, KitVersion: *kitVersion, ModelPin: *modelPin, Bare: *bare, CLIVersion: version})
	if err != nil {
		fatal(err)
	}
	if err := engine.WriteOutput(*out, *format, result); err != nil {
		fatal(err)
	}
	if result.Status != "PASSED" {
		fmt.Fprintf(os.Stderr, "Validation %s: %d finding(s). Evidence: %s\n", result.Status, len(result.Findings), *out)
		os.Exit(1)
	}
	fmt.Printf("Validation PASSED. Evidence: %s\n", *out)
}

func sync(args []string) {
	fs := flag.NewFlagSet("sync", flag.ExitOnError)
	configPath := fs.String("config", engine.DefaultConfigPath, "AI-SDLC configuration path")
	config, err := engine.LoadConfigIfPresent(*configPath)
	if err != nil {
		fatal(err)
	}
	resultPath := fs.String("result", "validation-result.json", "Validation JSON produced by validate")
	apiURL := fs.String("api-url", config.APIURL, "Management API root, e.g. https://control.example.com")
	projectID := fs.String("project", config.Project, "Project UUID")
	token := fs.String("token", "", "OAuth2 access token (env or stored token if omitted)")
	idempotencyKey := fs.String("idempotency-key", "", "Stable key for retry-safe evidence synchronization")
	retries := fs.Int("retries", 4, "Maximum sync attempts for transport, 429 and 5xx failures")
	_ = fs.Parse(args)
	resolvedToken, _, err := engine.ResolveToken(*token)
	if err != nil {
		fatal(err)
	}
	if *apiURL == "" || *projectID == "" || *idempotencyKey == "" {
		fatal(errors.New("api-url, project and idempotency-key are required for sync"))
	}
	if err := engine.SyncWithOptions(engine.SyncOptions{ResultPath: *resultPath, APIURL: *apiURL, ProjectID: *projectID, Token: resolvedToken, IdempotencyKey: *idempotencyKey, MaxAttempts: *retries}); err != nil {
		fatal(err)
	}
	fmt.Println("Validation evidence synchronized.")
}

func initConfig(args []string) {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	configPath := fs.String("config", engine.DefaultConfigPath, "Configuration file path")
	project := fs.String("project", "", "Project UUID")
	apiURL := fs.String("api-url", "", "Management API root")
	specDir := fs.String("spec-dir", ".", "Spec directory")
	kitVersion := fs.String("kit-version", "unversioned", "Resolved kit version")
	model := fs.String("model", "", "Pinned model reference")
	force := fs.Bool("force", false, "Replace existing config")
	_ = fs.Parse(args)
	if *model != "" && !strings.Contains(*model, "@") {
		fatal(errors.New("model must include an immutable @revision"))
	}
	if err := engine.WriteConfig(*configPath, engine.Config{Project: *project, APIURL: strings.TrimRight(*apiURL, "/"), SpecDir: *specDir, KitVersion: *kitVersion, ModelPin: *model}, *force); err != nil {
		fatal(err)
	}
	fmt.Printf("Created %s. Review and commit this governance configuration; it contains no credentials.\n", *configPath)
}

func login(args []string) {
	fs := flag.NewFlagSet("login", flag.ExitOnError)
	tokenURL := fs.String("token-url", os.Getenv("AISDLC_TOKEN_URL"), "Keycloak token endpoint")
	clientID := fs.String("client-id", "aisdlc-cli", "OAuth2 client ID")
	clientSecret := fs.String("client-secret", os.Getenv("AISDLC_CLIENT_SECRET"), "OAuth2 client secret (or AISDLC_CLIENT_SECRET)")
	tokenFile := fs.String("token-file", engine.DefaultTokenPath(), "Local token storage path")
	_ = fs.Parse(args)
	if *tokenURL == "" || *clientSecret == "" {
		fatal(errors.New("token-url and client-secret are required for client-credentials login"))
	}
	form := url.Values{"grant_type": {"client_credentials"}, "client_id": {*clientID}, "client_secret": {*clientSecret}}
	request, err := http.NewRequest(http.MethodPost, *tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		fatal(err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := (&http.Client{Timeout: 15 * time.Second}).Do(request)
	if err != nil {
		fatal(err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 8192))
	if response.StatusCode < 200 || response.StatusCode > 299 {
		fatal(fmt.Errorf("login failed with HTTP %s: %s", response.Status, strings.TrimSpace(string(body))))
	}
	var payload struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		fatal(err)
	}
	if err := engine.SaveToken(*tokenFile, engine.StoredToken{AccessToken: payload.AccessToken, ExpiresAt: time.Now().Add(time.Duration(payload.ExpiresIn) * time.Second)}); err != nil {
		fatal(err)
	}
	fmt.Printf("Stored a client-credentials token in %s with restrictive file permissions.\n", *tokenFile)
}

func status(args []string) {
	fs := flag.NewFlagSet("status", flag.ExitOnError)
	configPath := fs.String("config", engine.DefaultConfigPath, "AI-SDLC configuration path")
	resultPath := fs.String("result", "validation-result.json", "Last validation result path")
	asJSON := fs.Bool("json", false, "Emit machine-readable JSON")
	_ = fs.Parse(args)
	report, err := engine.ReadStatus(*configPath, *resultPath)
	if err != nil {
		fatal(err)
	}
	if *asJSON {
		encoded, _ := json.MarshalIndent(report, "", "  ")
		fmt.Println(string(encoded))
		return
	}
	fmt.Printf("configured=%t project=%s api=%s last_validation=%s findings=%d\n", report.Configured, report.Project, report.APIURL, report.LastValidation, report.FindingCount)
}

func usage() {
	fmt.Fprintln(os.Stderr, "AI-SDLC deterministic validator\n\nCommands:\n  aisdlc init --project <uuid> --api-url https://control.example.com --model provider/model@revision\n  aisdlc login --token-url https://auth.example/realms/ai-sdlc/protocol/openid-connect/token --client-secret <secret>\n  aisdlc validate --config .aisdlc.yml --format json|junit|sarif --out validation-result.json\n  aisdlc sync --config .aisdlc.yml --result validation-result.json --idempotency-key <key>\n  aisdlc status --config .aisdlc.yml --json")
}
func fatal(err error) { fmt.Fprintln(os.Stderr, "error:", err); os.Exit(2) }
