export type NotificationChannelType = "EMAIL" | "SLACK_WEBHOOK" | "TEAMS_WEBHOOK" | "GENERIC_WEBHOOK";

export interface AiSdlcClientOptions {
  baseUrl: string;
  accessToken: string;
  fetch?: typeof globalThis.fetch;
}

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; }
export interface ScmEvent { id: string; eventType: string; deliveryId: string; receivedAt: string; }
export interface RiskScore { id: string; score: number; band: "LOW" | "MODERATE" | "HIGH" | "CRITICAL"; formulaVersion: string; computedAt: string; components?: Record<string, number>; }
export interface NotificationChannel { id: string; type: NotificationChannelType; name: string; enabled: boolean; destinationFingerprint: string; }
export interface CreateNotificationChannel { type: NotificationChannelType; name: string; destination: string; sharedSecret?: string; }
export interface Approval { id: string; title: string; status: "PENDING" | "APPROVED" | "REJECTED" | "ESCALATED"; quorum: number; dueAt: string; }

export class AiSdlcApiError extends Error {
  readonly status: number;
  readonly body: unknown;
  constructor(status: number, body: unknown) { super(`AI-SDLC API request failed with HTTP ${status}`); this.status = status; this.body = body; }
}

/** A browser- and Node-compatible client for the stable v1 integration routes. */
export class AiSdlcClient {
  private readonly baseUrl: string;
  private readonly accessToken: string;
  private readonly fetchImpl: typeof globalThis.fetch;
  constructor(options: AiSdlcClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.accessToken = options.accessToken;
    this.fetchImpl = options.fetch ?? globalThis.fetch;
    if (!this.fetchImpl) throw new Error("A Fetch-compatible implementation is required.");
  }
  listScmEvents(projectId: string, page = 0, size = 25): Promise<Page<ScmEvent>> { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/scm-events?page=${page}&size=${size}`); }
  getLatestRiskScore(projectId: string): Promise<RiskScore> { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/risk-intelligence/latest`); }
  listNotificationChannels(projectId: string): Promise<NotificationChannel[]> { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/notification-channels`); }
  createNotificationChannel(projectId: string, input: CreateNotificationChannel): Promise<{ id: string }> { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/notification-channels`, { method: "POST", body: JSON.stringify(input) }); }
  setNotificationChannelEnabled(projectId: string, channelId: string, enabled: boolean): Promise<void> { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/notification-channels/${encodeURIComponent(channelId)}`, { method: "PATCH", body: JSON.stringify({ enabled }) }).then(() => undefined); }
  listApprovals(projectId: string, page = 0, size = 25): Promise<Page<Approval>> { return this.request(`/api/v1/projects/${encodeURIComponent(projectId)}/approvals?page=${page}&size=${size}`); }
  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.fetchImpl(this.baseUrl + path, { ...init, headers: { Accept: "application/json", Authorization: `Bearer ${this.accessToken}`, "Content-Type": "application/json", ...(init.headers ?? {}) } });
    const text = await response.text();
    const body: unknown = text ? JSON.parse(text) : undefined;
    if (!response.ok) throw new AiSdlcApiError(response.status, body);
    return body as T;
  }
}
