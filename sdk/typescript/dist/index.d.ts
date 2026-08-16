export type NotificationChannelType = "EMAIL" | "SLACK_WEBHOOK" | "TEAMS_WEBHOOK" | "GENERIC_WEBHOOK";
export interface AiSdlcClientOptions {
    baseUrl: string;
    accessToken: string;
    fetch?: typeof globalThis.fetch;
}
export interface Page<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}
export interface ScmEvent {
    id: string;
    eventType: string;
    deliveryId: string;
    receivedAt: string;
}
export interface RiskScore {
    id: string;
    score: number;
    band: "LOW" | "MODERATE" | "HIGH" | "CRITICAL";
    formulaVersion: string;
    computedAt: string;
    components?: Record<string, number>;
}
export interface NotificationChannel {
    id: string;
    type: NotificationChannelType;
    name: string;
    enabled: boolean;
    destinationFingerprint: string;
}
export interface CreateNotificationChannel {
    type: NotificationChannelType;
    name: string;
    destination: string;
    sharedSecret?: string;
}
export interface Approval {
    id: string;
    title: string;
    status: "PENDING" | "APPROVED" | "REJECTED" | "ESCALATED";
    quorum: number;
    dueAt: string;
}
export declare class AiSdlcApiError extends Error {
    readonly status: number;
    readonly body: unknown;
    constructor(status: number, body: unknown);
}
/** A browser- and Node-compatible client for the stable v1 integration routes. */
export declare class AiSdlcClient {
    private readonly baseUrl;
    private readonly accessToken;
    private readonly fetchImpl;
    constructor(options: AiSdlcClientOptions);
    listScmEvents(projectId: string, page?: number, size?: number): Promise<Page<ScmEvent>>;
    getLatestRiskScore(projectId: string): Promise<RiskScore>;
    listNotificationChannels(projectId: string): Promise<NotificationChannel[]>;
    createNotificationChannel(projectId: string, input: CreateNotificationChannel): Promise<{
        id: string;
    }>;
    setNotificationChannelEnabled(projectId: string, channelId: string, enabled: boolean): Promise<void>;
    listApprovals(projectId: string, page?: number, size?: number): Promise<Page<Approval>>;
    private request;
}
