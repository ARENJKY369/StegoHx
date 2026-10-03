/**
 * Typed client for the StegoHX API. All requests go through the same-origin
 * /api/v1/* route handler, which proxies to the Java engine (and falls back
 * to the analyzer service for scans when the engine is offline).
 */

export type Verdict =
  | "CLEAN"
  | "INCONCLUSIVE"
  | "LIKELY_STEGO"
  | "HIGH_CONFIDENCE_STEGO";

export type RecommendedAction =
  | "NONE"
  | "FLAG_FOR_REVIEW"
  | "EXTRACT_ATTEMPT"
  | "CLEAN_SANITIZE"
  | "QUARANTINE";

export interface FileMeta {
  name: string;
  size_bytes: number;
  mime: string;
  kind: "image" | "audio" | "video" | "other" | string;
  width?: number;
  height?: number;
  duration_samples?: number;
  sample_rate?: number;
}

export interface AnalyzerFinding {
  name: string;
  display_name: string;
  applicable: boolean;
  score: number;
  weight: number;
  notes: string;
  details?: Record<string, unknown>;
}

export interface AnalyzerAnalysis {
  analyzer_version: string;
  file: FileMeta;
  threat_score: number;
  confidence: number;
  verdict: Verdict | string;
  summary: string;
  recommended_action: RecommendedAction | string;
  analyzers: AnalyzerFinding[];
  model_used: string;
  analysis_ms: number;
}

export interface ScanResponse {
  scan_id: string;
  created_at: string;
  engine_version: string;
  analysis: AnalyzerAnalysis;
}

export interface ModuleInfo {
  id: string;
  display_name: string;
  description: string;
  media_kind: string;
  requires_cover: boolean;
}

export interface SystemStatus {
  status: string;
  engine: {
    version: string | null;
    java_version: string | null;
    uptime_seconds: number;
  };
  analyzer: {
    reachable: boolean;
    base_url: string;
    version: string | null;
    model_loaded: boolean;
  };
  scans_executed: number;
  modules: ModuleInfo[];
}

export interface HideResponse {
  module_id: string;
  module_name: string;
  output_name: string;
  output_mime: string;
  output_base64: string;
  payload_bytes: number;
  capacity_used_ratio: number;
}

export interface ExtractResponse {
  module_id: string;
  module_name: string;
  payload_text: string | null;
  payload_base64: string;
  payload_bytes: number;
  container_verified: boolean;
}

export interface CleanResponse {
  module_id: string;
  module_name: string;
  output_mime: string;
  output_base64: string;
  note: string;
}

export interface EvolveCandidate {
  strategy: { label: string; bit_depth: number; spread: string };
  ok: boolean;
  threat_score: number;
  payload_bytes: number;
  capacity_used: number;
  error: string | null;
}

export interface EvolveResponse {
  baseline_threat_score: number;
  selected: EvolveCandidate | null;
  candidates: EvolveCandidate[];
  advice: string;
}

export interface ApiError {
  error: string;
  message: string;
  hint?: string;
  status: number;
}

export class ApiRequestError extends Error {
  constructor(public readonly detail: ApiError) {
    super(detail.message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1/${path}`, init);
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  if (!res.ok) {
    const detail = (body as Partial<ApiError>) ?? {};
    throw new ApiRequestError({
      error: detail.error ?? res.statusText,
      message: detail.message ?? `request failed (HTTP ${res.status})`,
      hint: detail.hint,
      status: res.status,
    });
  }
  return body as T;
}

export const api = {
  systemStatus: () => request<SystemStatus>("system/status"),

  scan: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<ScanResponse>("scan", { method: "POST", body: form });
  },

  recentScans: (limit = 10) => request<ScanResponse[]>(`scans?limit=${limit}`),

  hide: (form: FormData) => request<HideResponse>("stego/hide", { method: "POST", body: form }),

  extract: (form: FormData) => request<ExtractResponse>("stego/extract", { method: "POST", body: form }),

  clean: (form: FormData) => request<CleanResponse>("stego/clean", { method: "POST", body: form }),

  evolve: (form: FormData) => request<EvolveResponse>("stego/evolve", { method: "POST", body: form }),

  reportUrl: (scanId: string) => `/api/v1/scans/${scanId}/report.pdf`,
};

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

export function verdictColor(verdict: string): string {
  switch (verdict) {
    case "HIGH_CONFIDENCE_STEGO":
      return "var(--danger)";
    case "LIKELY_STEGO":
      return "var(--warn-high)";
    case "INCONCLUSIVE":
      return "var(--warn)";
    default:
      return "var(--ok)";
  }
}

export function base64ToBlob(base64: string, mime: string): Blob {
  const bin = atob(base64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: mime });
}
