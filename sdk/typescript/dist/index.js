export class AiSdlcApiError extends Error {
    status;
    body;
    constructor(status, body) { super(`AI-SDLC API request failed with HTTP ${status}`); this.status = status; this.body = body; }
}
/** A browser- and Node-compatible client for the stable v1 integration routes. */
export class AiSdlcClient {
    baseUrl;
    accessToken;
    fetchImpl;
    constructor(options) {
        this.baseUrl = options.baseUrl.replace(/\/$/, "");
        this.accessToken = options.accessToken;
        this.fetchImpl = options.fetch ?? globalThis.fetch;
        if (!this.fetchImpl)
            throw new Error("A Fetch-compatible implementation is required.");
    }
    listScmEvents(projectId, page = 0, size = 25) { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/scm-events?page=${page}&size=${size}`); }
    getLatestRiskScore(projectId) { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/risk-intelligence/latest`); }
    listNotificationChannels(projectId) { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/notification-channels`); }
    createNotificationChannel(projectId, input) { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/notification-channels`, { method: "POST", body: JSON.stringify(input) }); }
    setNotificationChannelEnabled(projectId, channelId, enabled) { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/notification-channels/${encodeURIComponent(channelId)}`, { method: "PATCH", body: JSON.stringify({ enabled }) }).then(() => undefined); }
    listApprovals(projectId, page = 0, size = 25) { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/approvals?page=${page}&size=${size}`); }
    async request(path, init = {}) {
        const response = await this.fetchImpl(this.baseUrl + path, { ...init, headers: { Accept: "application/json", Authorization: `Bearer ${this.accessToken}`, "Content-Type": "application/json", ...(init.headers ?? {}) } });
        const text = await response.text();
        const body = text ? JSON.parse(text) : undefined;
        if (!response.ok)
            throw new AiSdlcApiError(response.status, body);
        return body;
    }
}
