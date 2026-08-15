package engine

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidateRejectsBareAndFloatingModel(t *testing.T) {
	if _, err := Validate(ValidateOptions{SpecDir: t.TempDir(), ModelPin: "provider/model@abc", Bare: true}); err == nil {
		t.Fatal("bare mode must be rejected")
	}
	if _, err := Validate(ValidateOptions{SpecDir: t.TempDir(), ModelPin: "provider/model:latest"}); err == nil {
		t.Fatal("floating model must be rejected")
	}
}
func TestValidateProducesDeterministicEvidence(t *testing.T) {
	dir := t.TempDir()
	contents := map[string]string{
		"constitution.md": "# Engineering Constitution\nVersion: 1.0.0\n",
		"spec.md":         "# Product specification\n",
		"tasks.md":        "# Delivery tasks\n- [ ] Implement governed workflow\n",
	}
	for name, content := range contents {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	one, err := Validate(ValidateOptions{SpecDir: dir, KitVersion: "kit@1", ModelPin: "provider/model@abc", CLIVersion: "test"})
	if err != nil {
		t.Fatal(err)
	}
	two, err := Validate(ValidateOptions{SpecDir: dir, KitVersion: "kit@1", ModelPin: "provider/model@abc", CLIVersion: "test"})
	if err != nil {
		t.Fatal(err)
	}
	if one.Status != "PASSED" || len(one.Evidence) != 1 {
		t.Fatalf("unexpected result: %#v", one)
	}
	if one.Evidence[0].DigestSHA256 != two.Evidence[0].DigestSHA256 {
		t.Fatal("identical file tree must have identical digest")
	}
}
func TestValidateFindsRequiredArtifacts(t *testing.T) {
	result, err := Validate(ValidateOptions{SpecDir: t.TempDir(), ModelPin: "provider/model@abc"})
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "FAILED" || len(result.Findings) != 3 {
		t.Fatalf("expected 3 deterministic findings, got %#v", result)
	}
}

func TestValidateFindsUnversionedAndUnstructuredGovernanceArtifacts(t *testing.T) {
	dir := t.TempDir()
	fixtures := map[string]string{"constitution.md": "principles", "spec.md": "plain prose", "tasks.md": "implement it"}
	for name, content := range fixtures {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	result, err := Validate(ValidateOptions{SpecDir: dir, ModelPin: "provider/model@abc"})
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "FAILED" || len(result.Findings) != 3 {
		t.Fatalf("expected exactly three structure findings, got %#v", result)
	}
}
