package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type Finding struct {
	Severity    string `json:"severity"`
	Code        string `json:"code"`
	Message     string `json:"message"`
	Path        string `json:"path,omitempty"`
	Line        *int   `json:"line,omitempty"`
	EvidenceURI string `json:"evidenceUri,omitempty"`
}
type Evidence struct {
	Type         string `json:"type"`
	DigestSHA256 string `json:"digestSha256"`
	URI          string `json:"uri,omitempty"`
}
type Result struct {
	Status     string     `json:"status"`
	CLIVersion string     `json:"cliVersion"`
	KitVersion string     `json:"kitVersion"`
	ModelPin   string     `json:"modelPin"`
	Bare       bool       `json:"bare"`
	Findings   []Finding  `json:"findings"`
	Evidence   []Evidence `json:"evidence"`
}
type ValidateOptions struct {
	SpecDir, KitVersion, ModelPin, CLIVersion string
	Bare                                      bool
}

func Validate(input ValidateOptions) (Result, error) {
	if input.Bare {
		return Result{}, errors.New("--bare is prohibited by AI-SDLC governance")
	}
	if !isPinned(input.ModelPin) {
		return Result{}, errors.New("--model must be a revision-pinned reference such as provider/model@revision; latest and floating aliases are prohibited")
	}
	result := Result{Status: "PASSED", CLIVersion: input.CLIVersion, KitVersion: input.KitVersion, ModelPin: input.ModelPin, Bare: false, Findings: []Finding{}, Evidence: []Evidence{}}
	files, err := collectFiles(input.SpecDir)
	if err != nil {
		return Result{}, err
	}
	checks := []struct{ name, code, message string }{
		{"constitution.md", "AISDLC-CONSTITUTION-MISSING", "A versioned constitution is required."},
		{"spec.md", "AISDLC-SPEC-MISSING", "At least one feature specification (spec.md) is required."},
		{"tasks.md", "AISDLC-TASKS-MISSING", "At least one implementation task list (tasks.md) is required."},
	}
	for _, check := range checks {
		if !hasBaseName(files, check.name) {
			result.Findings = append(result.Findings, Finding{Severity: "HIGH", Code: check.code, Message: check.message})
		}
	}
	for _, required := range []string{"constitution.md", "spec.md", "tasks.md"} {
		if path := findBaseName(files, required); path != "" {
			content, readErr := os.ReadFile(path)
			if readErr != nil {
				return Result{}, readErr
			}
			if strings.TrimSpace(string(content)) == "" {
				result.Findings = append(result.Findings, Finding{Severity: "HIGH", Code: "AISDLC-ARTIFACT-EMPTY", Message: required + " must not be empty.", Path: filepath.ToSlash(path)})
			}
			if required == "constitution.md" && !hasVersionDeclaration(string(content)) {
				result.Findings = append(result.Findings, Finding{Severity: "MEDIUM", Code: "AISDLC-CONSTITUTION-UNVERSIONED", Message: "Constitution must declare an explicit version or revision.", Path: filepath.ToSlash(path)})
			}
			if required == "spec.md" && !hasHeading(string(content)) {
				result.Findings = append(result.Findings, Finding{Severity: "MEDIUM", Code: "AISDLC-SPEC-UNSTRUCTURED", Message: "Specification must contain at least one Markdown heading.", Path: filepath.ToSlash(path)})
			}
			if required == "tasks.md" && !hasTaskItem(string(content)) {
				result.Findings = append(result.Findings, Finding{Severity: "MEDIUM", Code: "AISDLC-TASKS-UNSTRUCTURED", Message: "Task list must contain at least one Markdown checkbox item.", Path: filepath.ToSlash(path)})
			}
		}
	}
	if len(result.Findings) > 0 {
		result.Status = "FAILED"
	}
	digest, err := digestFiles(input.SpecDir, files)
	if err != nil {
		return Result{}, err
	}
	result.Evidence = append(result.Evidence, Evidence{Type: "spec-kit-tree", DigestSHA256: digest, URI: "file://" + filepath.ToSlash(input.SpecDir)})
	return result, nil
}

func WriteResult(path string, result Result) error {
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(encoded, '\n'), 0o600)
}
func ReadResult(path string) (Result, error) {
	encoded, err := os.ReadFile(path)
	if err != nil {
		return Result{}, err
	}
	var result Result
	if err := json.Unmarshal(encoded, &result); err != nil {
		return Result{}, err
	}
	return result, nil
}
func isPinned(model string) bool {
	return strings.Contains(model, "@") && !strings.HasSuffix(model, "@") && !strings.Contains(strings.ToLower(model), "latest") && !strings.Contains(model, "*")
}
func collectFiles(root string) ([]string, error) {
	var files []string
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(files)
	return files, nil
}
func hasBaseName(files []string, wanted string) bool {
	for _, file := range files {
		if strings.EqualFold(filepath.Base(file), wanted) {
			return true
		}
	}
	return false
}
func findBaseName(files []string, wanted string) string {
	for _, file := range files {
		if strings.EqualFold(filepath.Base(file), wanted) {
			return file
		}
	}
	return ""
}
func hasHeading(content string) bool {
	for _, line := range strings.Split(content, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "#") {
			return true
		}
	}
	return false
}
func hasTaskItem(content string) bool {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "- [ ]") || strings.HasPrefix(line, "- [x]") || strings.HasPrefix(line, "- [X]") {
			return true
		}
	}
	return false
}
func hasVersionDeclaration(content string) bool {
	lower := strings.ToLower(content)
	return strings.Contains(lower, "version") || strings.Contains(lower, "revision")
}
func digestFiles(root string, files []string) (string, error) {
	hash := sha256.New()
	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			return "", err
		}
		rel, err := filepath.Rel(root, file)
		if err != nil {
			return "", err
		}
		hash.Write([]byte(filepath.ToSlash(rel)))
		hash.Write([]byte{0})
		hash.Write(data)
		hash.Write([]byte{0})
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
