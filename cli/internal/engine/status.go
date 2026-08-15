package engine

import "fmt"

type Status struct {
	ConfigPath     string `json:"configPath"`
	Configured     bool   `json:"configured"`
	Project        string `json:"project,omitempty"`
	APIURL         string `json:"apiUrl,omitempty"`
	LastValidation string `json:"lastValidation,omitempty"`
	FindingCount   int    `json:"findingCount"`
}

func ReadStatus(configPath, resultPath string) (Status, error) {
	config, err := LoadConfig(configPath)
	if err != nil {
		return Status{ConfigPath: configPath}, err
	}
	status := Status{ConfigPath: configPath, Configured: true, Project: config.Project, APIURL: config.APIURL}
	if resultPath == "" {
		return status, nil
	}
	result, err := ReadResult(resultPath)
	if err != nil {
		return status, fmt.Errorf("configured but cannot read validation result: %w", err)
	}
	status.LastValidation, status.FindingCount = result.Status, len(result.Findings)
	return status, nil
}
