package engine

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteCIOutputFormats(t *testing.T) {
	result := Result{CLIVersion: "test", Findings: []Finding{{Severity: "HIGH", Code: "AISDLC-SPEC-MISSING", Message: "Spec is required.", Path: "spec.md"}}}
	dir := t.TempDir()
	for format, fragment := range map[string]string{"junit": "testsuite", "sarif": "AISDLC-SPEC-MISSING"} {
		path := filepath.Join(dir, format+".out")
		if err := WriteOutput(path, format, result); err != nil {
			t.Fatalf("%s: %v", format, err)
		}
		content, err := os.ReadFile(path)
		if err != nil || !strings.Contains(string(content), fragment) {
			t.Fatalf("%s output is invalid: %s %v", format, content, err)
		}
	}
	if err := WriteOutput(filepath.Join(dir, "bad.out"), "xml", result); err == nil {
		t.Fatal("unsupported format must fail")
	}
}
