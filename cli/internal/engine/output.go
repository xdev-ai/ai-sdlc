package engine

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"os"
	"strings"
)

func WriteOutput(path, format string, result Result) error {
	switch strings.ToLower(format) {
	case "json":
		return WriteResult(path, result)
	case "junit", "junit-xml":
		return writeJUnit(path, result)
	case "sarif":
		return writeSARIF(path, result)
	default:
		return fmt.Errorf("unsupported output format %q; choose json, junit, or sarif", format)
	}
}

type junitSuite struct {
	XMLName   xml.Name    `xml:"testsuite"`
	Name      string      `xml:"name,attr"`
	Tests     int         `xml:"tests,attr"`
	Failures  int         `xml:"failures,attr"`
	TestCases []junitCase `xml:"testcase"`
}
type junitCase struct {
	Name      string        `xml:"name,attr"`
	ClassName string        `xml:"classname,attr"`
	Failure   *junitFailure `xml:"failure,omitempty"`
}
type junitFailure struct {
	Type    string `xml:"type,attr"`
	Message string `xml:"message,attr"`
}

func writeJUnit(path string, result Result) error {
	suite := junitSuite{Name: "ai-sdlc.validation", Tests: len(result.Findings)}
	if len(result.Findings) == 0 {
		suite.Tests = 1
		suite.TestCases = append(suite.TestCases, junitCase{Name: "deterministic-governance-validation", ClassName: "ai-sdlc"})
	}
	for _, finding := range result.Findings {
		suite.Failures++
		suite.TestCases = append(suite.TestCases, junitCase{Name: finding.Code, ClassName: "ai-sdlc", Failure: &junitFailure{Type: finding.Severity, Message: finding.Message}})
	}
	encoded, err := xml.MarshalIndent(suite, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append([]byte(xml.Header), append(encoded, '\n')...), 0o600)
}

func writeSARIF(path string, result Result) error {
	rules := make([]map[string]any, 0, len(result.Findings))
	findings := make([]map[string]any, 0, len(result.Findings))
	for _, finding := range result.Findings {
		rules = append(rules, map[string]any{"id": finding.Code, "name": finding.Code, "shortDescription": map[string]string{"text": finding.Message}})
		entry := map[string]any{"ruleId": finding.Code, "level": sarifLevel(finding.Severity), "message": map[string]string{"text": finding.Message}}
		if finding.Path != "" {
			entry["locations"] = []map[string]any{{"physicalLocation": map[string]any{"artifactLocation": map[string]string{"uri": finding.Path}}}}
		}
		findings = append(findings, entry)
	}
	document := map[string]any{"$schema": "https://json.schemastore.org/sarif-2.1.0.json", "version": "2.1.0", "runs": []map[string]any{{"tool": map[string]any{"driver": map[string]any{"name": "aisdlc", "version": result.CLIVersion, "rules": rules}}, "results": findings}}}
	encoded, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(encoded, '\n'), 0o600)
}

func sarifLevel(severity string) string {
	switch severity {
	case "CRITICAL", "HIGH":
		return "error"
	case "MEDIUM":
		return "warning"
	default:
		return "note"
	}
}
