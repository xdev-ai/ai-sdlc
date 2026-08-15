package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type UploadOptions struct {
	FilePath, APIURL, ProjectID, Token, AssetType, AccessLevel, ValidationEvidenceID, IdempotencyKey string
	MaxAttempts                                                                                      int
	Client                                                                                           *http.Client
}

type UploadResult struct {
	FilePath       string `json:"filePath"`
	SHA256         string `json:"sha256"`
	IdempotencyKey string `json:"idempotencyKey"`
	ContentType    string `json:"contentType"`
	Attempts       int    `json:"attempts"`
}

func UploadEvidence(options UploadOptions) (UploadResult, error) {
	if options.MaxAttempts == 0 {
		options.MaxAttempts = 4
	}
	if options.MaxAttempts < 1 {
		return UploadResult{}, fmt.Errorf("max attempts must be positive")
	}
	if options.FilePath == "" || options.APIURL == "" || options.ProjectID == "" || options.Token == "" {
		return UploadResult{}, fmt.Errorf("file, api-url, project and token are required for upload")
	}
	info, err := os.Stat(options.FilePath)
	if err != nil {
		return UploadResult{}, err
	}
	if !info.Mode().IsRegular() || info.Size() == 0 {
		return UploadResult{}, fmt.Errorf("upload file must be a non-empty regular file")
	}
	digest, err := fileSHA256(options.FilePath)
	if err != nil {
		return UploadResult{}, err
	}
	if options.AssetType == "" {
		options.AssetType = "VALIDATION"
	}
	if options.AccessLevel == "" {
		options.AccessLevel = "PROJECT"
	}
	if options.IdempotencyKey == "" {
		basis := strings.Join([]string{"evidence-upload-v1", options.AssetType, options.AccessLevel, options.ValidationEvidenceID, digest}, "\n")
		sum := sha256.Sum256([]byte(basis))
		options.IdempotencyKey = "upload-" + hex.EncodeToString(sum[:])
	}
	if options.Client == nil {
		options.Client = &http.Client{Timeout: 60 * time.Second}
	}
	contentType := mime.TypeByExtension(filepath.Ext(options.FilePath))
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	endpoint := strings.TrimRight(options.APIURL, "/") + "/api/v1/projects/" + url.PathEscape(options.ProjectID) + "/evidence-assets"
	var lastErr error
	for attempt := 1; attempt <= options.MaxAttempts; attempt++ {
		req, err := multipartUploadRequest(endpoint, options, digest, contentType)
		if err != nil {
			return UploadResult{}, err
		}
		response, err := options.Client.Do(req)
		if err == nil && response.StatusCode >= 200 && response.StatusCode <= 299 {
			response.Body.Close()
			return UploadResult{FilePath: options.FilePath, SHA256: digest, IdempotencyKey: options.IdempotencyKey, ContentType: contentType, Attempts: attempt}, nil
		}
		if err != nil {
			lastErr = err
		} else {
			message, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
			response.Body.Close()
			if response.StatusCode == http.StatusConflict {
				return UploadResult{}, fmt.Errorf("evidence upload idempotency conflict for key %q: %s", options.IdempotencyKey, strings.TrimSpace(string(message)))
			}
			lastErr = fmt.Errorf("evidence upload failed with HTTP %s: %s", response.Status, strings.TrimSpace(string(message)))
			if response.StatusCode != http.StatusTooManyRequests && response.StatusCode < 500 {
				return UploadResult{}, lastErr
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
	return UploadResult{}, fmt.Errorf("evidence upload exhausted %d attempts: %w", options.MaxAttempts, lastErr)
}

func multipartUploadRequest(endpoint string, options UploadOptions, digest, contentType string) (*http.Request, error) {
	reader, writer := io.Pipe()
	form := multipart.NewWriter(writer)
	go func() {
		defer writer.Close()
		defer form.Close()
		for key, value := range map[string]string{"assetType": options.AssetType, "accessLevel": options.AccessLevel} {
			if err := form.WriteField(key, value); err != nil {
				_ = writer.CloseWithError(err)
				return
			}
		}
		if options.ValidationEvidenceID != "" {
			if err := form.WriteField("validationEvidenceId", options.ValidationEvidenceID); err != nil {
				_ = writer.CloseWithError(err)
				return
			}
		}
		headers := make(textproto.MIMEHeader)
		headers.Set("Content-Disposition", mime.FormatMediaType("form-data", map[string]string{"name": "file", "filename": filepath.Base(options.FilePath)}))
		headers.Set("Content-Type", contentType)
		part, err := form.CreatePart(headers)
		if err != nil {
			_ = writer.CloseWithError(err)
			return
		}
		file, err := os.Open(options.FilePath)
		if err != nil {
			_ = writer.CloseWithError(err)
			return
		}
		defer file.Close()
		if _, err = io.Copy(part, file); err != nil {
			_ = writer.CloseWithError(err)
		}
	}()
	req, err := http.NewRequest(http.MethodPost, endpoint, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+options.Token)
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("X-Content-SHA256", digest)
	req.Header.Set("Idempotency-Key", options.IdempotencyKey)
	return req, nil
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err = io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
