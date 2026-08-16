package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

type client struct {
	baseURL, token, projectID string
	http                      *http.Client
}

func (c *client) request(ctx context.Context, method, path string, body any, target any) error {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.baseURL, "/")+path, reader)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+c.token)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	h := c.http
	if h == nil {
		h = http.DefaultClient
	}
	response, err := h.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	bytes, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode > 299 {
		return fmt.Errorf("AI-SDLC API returned HTTP %d: %s", response.StatusCode, string(bytes))
	}
	if target != nil && len(bytes) > 0 {
		return json.Unmarshal(bytes, target)
	}
	return nil
}
