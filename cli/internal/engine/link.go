package engine

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type LinkPullRequestOptions struct {
	APIURL, ProjectID, EventID, ValidationRunID, Token string
	MaxAttempts                                        int
	Client                                             *http.Client
}

// LinkPullRequest associates a received SCM pull-request event with an existing validation run.
// The SCM event itself is immutable; the server records the governed association in its audit ledger.
func LinkPullRequest(options LinkPullRequestOptions) error {
	if options.APIURL == "" || options.ProjectID == "" || options.EventID == "" || options.ValidationRunID == "" || options.Token == "" {
		return fmt.Errorf("api URL, project, SCM event, validation run and token are required")
	}
	if options.MaxAttempts == 0 {
		options.MaxAttempts = 4
	}
	if options.MaxAttempts < 1 {
		return fmt.Errorf("max attempts must be positive")
	}
	if options.Client == nil {
		options.Client = &http.Client{Timeout: 15 * time.Second}
	}
	body, err := json.Marshal(map[string]string{"validationRunId": options.ValidationRunID})
	if err != nil {
		return err
	}
	endpoint := strings.TrimRight(options.APIURL, "/") + "/api/v1/projects/" + options.ProjectID + "/scm-events/" + options.EventID + "/validation-run"
	var lastErr error
	for attempt := 1; attempt <= options.MaxAttempts; attempt++ {
		req, requestErr := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
		if requestErr != nil {
			return requestErr
		}
		req.Header.Set("Authorization", "Bearer "+options.Token)
		req.Header.Set("Content-Type", "application/json")
		response, requestErr := options.Client.Do(req)
		if requestErr == nil && response.StatusCode >= http.StatusOK && response.StatusCode <= 299 {
			response.Body.Close()
			return nil
		}
		if requestErr != nil {
			lastErr = requestErr
		} else {
			message, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
			response.Body.Close()
			lastErr = fmt.Errorf("pull-request link failed with HTTP %s: %s", response.Status, strings.TrimSpace(string(message)))
			if response.StatusCode != http.StatusTooManyRequests && response.StatusCode < 500 {
				return lastErr
			}
			if attempt < options.MaxAttempts {
				time.Sleep(retryDelay(attempt, response.Header.Get("Retry-After")))
			}
			continue
		}
		if attempt < options.MaxAttempts {
			time.Sleep(retryDelay(attempt, ""))
		}
	}
	return fmt.Errorf("pull-request link exhausted %d attempts: %w", options.MaxAttempts, lastErr)
}
