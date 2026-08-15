package main

import (
	"errors"
	"flag"
	"fmt"
	"os"

	"github.com/xdev-ai/ai-sdlc/cli/internal/engine"
)

const version = "0.1.0"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "validate":
		validate(os.Args[2:])
	case "sync":
		sync(os.Args[2:])
	case "version", "--version", "-v":
		fmt.Println(version)
	default:
		usage()
		os.Exit(2)
	}
}

func validate(args []string) {
	fs := flag.NewFlagSet("validate", flag.ExitOnError)
	specDir := fs.String("spec-dir", ".", "Spec Kit directory")
	kitVersion := fs.String("kit-version", "unversioned", "Resolved Spec Kit version")
	modelPin := fs.String("model", "", "Pinned AI model reference, e.g. provider/model@revision")
	bare := fs.Bool("bare", false, "Forbidden: bypass governed context")
	out := fs.String("out", "validation-result.json", "Validation evidence output")
	_ = fs.Parse(args)

	result, err := engine.Validate(engine.ValidateOptions{SpecDir: *specDir, KitVersion: *kitVersion, ModelPin: *modelPin, Bare: *bare, CLIVersion: version})
	if err != nil { fatal(err) }
	if err := engine.WriteResult(*out, result); err != nil { fatal(err) }
	if result.Status != "PASSED" {
		fmt.Fprintf(os.Stderr, "Validation %s: %d finding(s). Evidence: %s\n", result.Status, len(result.Findings), *out)
		os.Exit(1)
	}
	fmt.Printf("Validation PASSED. Evidence: %s\n", *out)
}

func sync(args []string) {
	fs := flag.NewFlagSet("sync", flag.ExitOnError)
	resultPath := fs.String("result", "validation-result.json", "Validation JSON produced by validate")
	apiURL := fs.String("api-url", "", "Management API root, e.g. https://control.example.com")
	projectID := fs.String("project", "", "Project UUID")
	token := fs.String("token", "", "OAuth2 access token (or set AISDLC_ACCESS_TOKEN)")
	idempotencyKey := fs.String("idempotency-key", "", "Stable key for retry-safe evidence synchronization")
	_ = fs.Parse(args)
	if *token == "" { *token = os.Getenv("AISDLC_ACCESS_TOKEN") }
	if *apiURL == "" || *projectID == "" || *token == "" || *idempotencyKey == "" { fatal(errors.New("api-url, project, token and idempotency-key are required for sync")) }
	if err := engine.Sync(*resultPath, *apiURL, *projectID, *token, *idempotencyKey); err != nil { fatal(err) }
	fmt.Println("Validation evidence synchronized.")
}

func usage() {
	fmt.Fprintln(os.Stderr, "AI-SDLC deterministic validator\n\nCommands:\n  aisdlc validate --spec-dir ./spec-kit --kit-version kit@1.2.0 --model provider/model@revision\n  aisdlc sync --result validation-result.json --api-url https://control.example.com --project <uuid> --idempotency-key <key>")
}
func fatal(err error) { fmt.Fprintln(os.Stderr, "error:", err); os.Exit(2) }

