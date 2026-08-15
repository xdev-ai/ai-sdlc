package engine

import (
	"bytes"
	"fmt"
	"net/http"
	"strings"
	"time"
)

func Sync(resultPath, apiURL, projectID, token, idempotencyKey string) error {
	result, err := ReadResult(resultPath)
	if err != nil { return err }
	body, err := jsonMarshal(result)
	if err != nil { return err }
	endpoint := strings.TrimRight(apiURL, "/") + "/api/v1/cli/projects/" + projectID + "/validation-runs"
	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil { return err }
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", idempotencyKey)
	client := &http.Client{Timeout: 15 * time.Second}
	response, err := client.Do(req)
	if err != nil { return err }
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode > 299 { return fmt.Errorf("evidence synchronization failed with HTTP %s", response.Status) }
	return nil
}

