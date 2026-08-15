package engine

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const DefaultConfigPath = ".aisdlc.yml"

type Config struct {
	Project    string
	APIURL     string
	SpecDir    string
	KitVersion string
	ModelPin   string
}

func DefaultConfig() Config {
	return Config{SpecDir: ".", KitVersion: "unversioned"}
}

func WriteConfig(path string, config Config, force bool) error {
	if _, err := os.Stat(path); err == nil && !force {
		return fmt.Errorf("%s already exists; use --force to replace it", path)
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	content := "# AI-SDLC deterministic validator configuration\n" +
		"# This file contains governance metadata, not credentials.\n" +
		"project: " + config.Project + "\n" +
		"api-url: " + config.APIURL + "\n" +
		"spec-dir: " + config.SpecDir + "\n" +
		"kit-version: " + config.KitVersion + "\n" +
		"model: " + config.ModelPin + "\n"
	return os.WriteFile(path, []byte(content), 0o644)
}

func LoadConfig(path string) (Config, error) {
	config := DefaultConfig()
	content, err := os.ReadFile(path)
	if err != nil {
		return config, err
	}
	for number, line := range strings.Split(string(content), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			return config, fmt.Errorf("invalid config entry on line %d", number+1)
		}
		key := strings.TrimSpace(parts[0])
		value := strings.Trim(strings.TrimSpace(parts[1]), "\"'")
		switch key {
		case "project":
			config.Project = value
		case "api-url":
			config.APIURL = strings.TrimRight(value, "/")
		case "spec-dir":
			config.SpecDir = value
		case "kit-version":
			config.KitVersion = value
		case "model":
			config.ModelPin = value
		default:
			return config, fmt.Errorf("unsupported config key %q on line %d", key, number+1)
		}
	}
	return config, nil
}

func LoadConfigIfPresent(path string) (Config, error) {
	config, err := LoadConfig(path)
	if errors.Is(err, os.ErrNotExist) {
		return DefaultConfig(), nil
	}
	return config, err
}

type StoredToken struct {
	AccessToken string    `json:"accessToken"`
	ExpiresAt   time.Time `json:"expiresAt"`
}

func DefaultTokenPath() string {
	if configured := os.Getenv("AISDLC_TOKEN_FILE"); configured != "" {
		return configured
	}
	if configHome := os.Getenv("XDG_CONFIG_HOME"); configHome != "" {
		return filepath.Join(configHome, "aisdlc", "token.json")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ".aisdlc-token.json"
	}
	return filepath.Join(home, ".config", "aisdlc", "token.json")
}

func SaveToken(path string, token StoredToken) error {
	if strings.TrimSpace(token.AccessToken) == "" {
		return errors.New("refusing to save an empty access token")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	encoded, err := json.MarshalIndent(token, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(encoded, '\n'), 0o600)
}

func LoadToken(path string) (StoredToken, error) {
	encoded, err := os.ReadFile(path)
	if err != nil {
		return StoredToken{}, err
	}
	var token StoredToken
	if err := json.Unmarshal(encoded, &token); err != nil {
		return StoredToken{}, err
	}
	if token.AccessToken == "" {
		return StoredToken{}, errors.New("stored token is empty")
	}
	if !token.ExpiresAt.IsZero() && !token.ExpiresAt.After(time.Now().Add(30*time.Second)) {
		return StoredToken{}, errors.New("stored token is expired or expires in less than 30 seconds; run aisdlc login")
	}
	return token, nil
}

func ResolveToken(flagToken string) (string, string, error) {
	if strings.TrimSpace(flagToken) != "" {
		return flagToken, "flag", nil
	}
	if fromEnvironment := os.Getenv("AISDLC_ACCESS_TOKEN"); strings.TrimSpace(fromEnvironment) != "" {
		return fromEnvironment, "AISDLC_ACCESS_TOKEN", nil
	}
	token, err := LoadToken(DefaultTokenPath())
	if err != nil {
		return "", "", fmt.Errorf("no usable token: provide --token, set AISDLC_ACCESS_TOKEN, or run aisdlc login: %w", err)
	}
	return token.AccessToken, DefaultTokenPath(), nil
}
