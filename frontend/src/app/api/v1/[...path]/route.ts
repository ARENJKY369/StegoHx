import { NextRequest, NextResponse } from "next/server";

/**
 * Same-origin API proxy.
 *
 * The browser console never talks to backend services directly. Every
 * /api/v1/* request is forwarded server-side to the Java engine. When the
 * engine is not running (e.g. preview deployments without Java), the proxy
 * degrades gracefully:
 *
 *   - POST /api/v1/scan  -> forwarded to the Python analyzer service and
 *     wrapped into the engine's ScanResponse shape (scan id "preview-*")
 *   - GET  /api/v1/system/status -> synthesized status with analyzer probe
 *   - everything else    -> 503 with a structured hint
 */

const ENGINE_URL = process.env.STEGOHX_ENGINE_URL ?? "http://127.0.0.1:8080";
const ANALYZER_URL = process.env.STEGOHX_ANALYZER_URL ?? "http://127.0.0.1:8000";

const HOP_HEADERS = new Set([
  "host",
  "connection",
  "content-length",
  "transfer-encoding",
  "keep-alive",
  "upgrade",
]);

async function forward(
  req: NextRequest,
  base: string,
  path: string,
  body: ArrayBuffer | undefined,
): Promise<Response> {
  const url = `${base}/api/v1/${path}${req.nextUrl.search}`;
  const headers = new Headers();
  req.headers.forEach((value, key) => {
    if (!HOP_HEADERS.has(key.toLowerCase())) headers.set(key, value);
  });
  const res = await fetch(url, {
    method: req.method,
    headers,
    body: body && body.byteLength > 0 ? body : undefined,
    redirect: "manual",
  });
  const outHeaders = new Headers();
  res.headers.forEach((value, key) => {
    if (!HOP_HEADERS.has(key.toLowerCase())) outHeaders.set(key, value);
  });
  return new Response(res.body, { status: res.status, headers: outHeaders });
}

function offline(path: string): Response {
  return NextResponse.json(
    {
      error: "engine_offline",
      message:
        "The StegoHX Java engine is not reachable. Start it with: cd backend && mvn spring-boot:run",
      hint: `probed ${ENGINE_URL}; request was ${path}`,
      status: 503,
    },
    { status: 503 },
  );
}

const FALLBACK_MODULES = [
  {
    id: "image_lsb",
    display_name: "Image LSB (RGB bit planes)",
    description:
      "Replaces the lowest 1-2 bit planes of RGB pixel channels with keyed, optionally scattered payload bits.",
    media_kind: "IMAGE",
    requires_cover: true,
  },
  {
    id: "audio_lsb_wav",
    display_name: "Audio LSB (PCM WAV)",
    description: "Replaces the lowest 1-2 bits of every 16-bit PCM sample with keyed payload bits.",
    media_kind: "AUDIO",
    requires_cover: true,
  },
  {
    id: "video_mp4_atom",
    display_name: "Video Container Metadata (MP4/MOV)",
    description: "Stores the keyed payload in a dedicated top-level 'stgx' atom.",
    media_kind: "VIDEO",
    requires_cover: true,
  },
  {
    id: "network_transport",
    display_name: "Network Transport Codec (DNS / HTTP shaping)",
    description: "Chunks the keyed payload into Base32 fragments shaped as DNS labels or HTTP headers.",
    media_kind: "NETWORK",
    requires_cover: false,
  },
  {
    id: "text_armored",
    display_name: "Text Armored Block (XOR + Base64)",
    description: "Keyed XOR container wrapped in PEM-style Base64 armor.",
    media_kind: "TEXT",
    requires_cover: false,
  },
];

async function fallbackStatus(): Promise<Response> {
  let analyzer: SystemStatusShape["analyzer"] = {
    reachable: false,
    base_url: ANALYZER_URL,
    version: null,
    model_loaded: false,
  };
  try {
    const res = await fetch(`${ANALYZER_URL}/health`, { signal: AbortSignal.timeout(2000) });
    if (res.ok) {
      const health = await res.json();
      analyzer = {
        reachable: true,
        base_url: ANALYZER_URL,
        version: health.version ?? null,
        model_loaded: Boolean(health.model_loaded),
      };
    }
  } catch {
    // analyzer unreachable too
  }
  const status: SystemStatusShape = {
    status: "PREVIEW (engine offline)",
    engine: { version: null, java_version: null, uptime_seconds: 0 },
    analyzer,
    scans_executed: 0,
    modules: FALLBACK_MODULES,
  };
  return NextResponse.json(status);
}

interface SystemStatusShape {
  status: string;
  engine: { version: string | null; java_version: string | null; uptime_seconds: number };
  analyzer: { reachable: boolean; base_url: string; version: string | null; model_loaded: boolean };
  scans_executed: number;
  modules: typeof FALLBACK_MODULES;
}

async function fallbackScan(req: NextRequest, body: ArrayBuffer | undefined): Promise<Response> {
  try {
    const url = `${ANALYZER_URL}/api/v1/analyze`;
    const headers = new Headers();
    req.headers.forEach((value, key) => {
      if (!HOP_HEADERS.has(key.toLowerCase())) headers.set(key, value);
    });
    const res = await fetch(url, {
      method: "POST",
      headers,
      body: body && body.byteLength > 0 ? body : undefined,
    });
    if (!res.ok) {
      return new Response(res.body, { status: res.status, headers: { "content-type": "application/json" } });
    }
    const analysis = await res.json();
    return NextResponse.json({
      scan_id: `preview-${crypto.randomUUID()}`,
      created_at: new Date().toISOString(),
      engine_version: "analyzer-fallback",
      analysis,
    });
  } catch (e) {
    return NextResponse.json(
      {
        error: "analyzer_offline",
        message: `Neither the engine nor the analyzer service is reachable (analyzer at ${ANALYZER_URL}).`,
        detail: e instanceof Error ? `${e.name}: ${e.message}${e.cause ? ` / cause: ${String(e.cause)}` : ""}` : String(e),
        status: 503,
      },
      { status: 503 },
    );
  }
}

type Ctx = { params: { path: string[] } };

async function handle(req: NextRequest, ctx: Ctx): Promise<Response> {
  const path = (ctx.params.path ?? []).join("/");
  // Buffer the request body once: the web request stream can only be read
  // a single time, and the fallback path needs the same bytes.
  const body = ["GET", "HEAD"].includes(req.method) ? undefined : await req.arrayBuffer();
  try {
    return await forward(req, ENGINE_URL, path, body);
  } catch {
    if (req.method === "POST" && path === "scan") {
      return fallbackScan(req, body);
    }
    if (req.method === "GET" && path === "system/status") {
      return fallbackStatus();
    }
    if (req.method === "GET" && path === "scans") {
      return NextResponse.json([]);
    }
    return offline(path);
  }
}

export async function GET(req: NextRequest, ctx: Ctx) {
  return handle(req, ctx);
}

export async function POST(req: NextRequest, ctx: Ctx) {
  return handle(req, ctx);
}

export async function OPTIONS() {
  return new Response(null, { status: 204 });
}
