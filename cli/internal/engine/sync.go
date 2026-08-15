package engine

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

func Sync(resultPath, apiURL, projectID, token, idempotencyKey string) error {
	return SyncWithOptions(SyncOptions{ResultPath: resultPath, APIURL: apiURL, ProjectID: projectID, Token: token, IdempotencyKey: idempotencyKey})
}

type SyncOptions struct {
	ResultPath, APIURL, ProjectID, Token, IdempotencyKey string
	MaxAttempts                                          int
	Client                                               *http.Client
}

func SyncWithOptions(options SyncOptions) error {
	if options.MaxAttempts == 0 {
		options.MaxAttempts = 4
	}
	if options.MaxAttempts < 1 {
		return fmt.Errorf("max attempts must be positive")
	}
	if options.Client == nil {
		options.Client = &http.Client{Timeout: 15 * time.Second}
	}
	resultPath, apiURL, projectID, token, idempotencyKey := options.ResultPath, options.APIURL, options.ProjectID, options.Token, options.IdempotencyKey
	result, err := ReadResult(resultPath)
	if err != nil {
		return err
	}
	body, err := jsonMarshal(result)
	if err != nil {
		return err
	}
	endpoint := strings.TrimRight(apiURL, "/") + "/api/v1/cli/projects/" + projectID + "/validation-runs"
	var lastErr error
	for attempt := 1; attempt <= options.MaxAttempts; attempt++ {
		req, requestErr := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
		if requestErr != nil {
			return requestErr
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Idempotency-Key", idempotencyKey)
		response, requestErr := options.Client.Do(req)
		if requestErr == nil && response.StatusCode >= 200 && response.StatusCode <= 299 {
			response.Body.Close()
			return nil
		}
		if requestErr != nil {
			lastErr = requestErr
		} else {
			message, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
			response.Body.Close()
			if response.StatusCode == http.StatusConflict {
				return fmt.Errorf("evidence synchronization conflict for idempotency key %q: %s", idempotencyKey, strings.TrimSpace(string(message)))
			}
			lastErr = fmt.Errorf("evidence synchronization failed with HTTP %s: %s", response.Status, strings.TrimSpace(string(message)))
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
	return fmt.Errorf("evidence synchronization exhausted %d attempts: %w", options.MaxAttempts, lastErr)
}

func retryDelay(attempt int, retryAfter string) time.Duration {
	if seconds, err := strconv.Atoi(retryAfter); err == nil && seconds > 0 && seconds <= 60 {
		return time.Duration(seconds) * time.Second
	}
	return time.Duration(1<<(attempt-1)) * time.Second
}
