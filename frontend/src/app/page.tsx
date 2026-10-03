"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, ScanResponse, SystemStatus, formatBytes, verdictColor } from "@/lib/api";

export default function Dashboard() {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [scans, setScans] = useState<ScanResponse[]>([]);

  useEffect(() => {
    api.systemStatus().then(setStatus).catch(() => setStatus(null));
    api.recentScans(8).then(setScans).catch(() => setScans([]));
  }, []);

  const engineOnline = status?.engine.version != null;
  const analyzerOnline = status?.analyzer.reachable === true;

  return (
    <div>
      <section className="hero">
        <div className="row">
          <span className="hero-badge">STEGANALYSIS · RESEARCH CONSOLE</span>
        </div>
        <h1>
          Stego<span style={{ color: "var(--accent)" }}>HX</span> Console
        </h1>
        <p className="sub">
          A local-first steganography workbench: embed and extract payloads across five carrier
          modules, run statistical steganalysis against media, sanitize suspicious files, and use
          analyzer feedback to study how embedding parameters affect detectability.
        </p>
        <div className="row">
          <Link href="/scan" className="btn btn-accent">
            Scan a file
          </Link>
          <Link href="/workbench" className="btn">
            Open workbench
          </Link>
        </div>
      </section>

      <section className="section">
        <div className="stat-grid">
          <div className="stat">
            <div className="stat-label">Engine</div>
            <div className="stat-value" style={{ color: engineOnline ? "var(--ok)" : "var(--text-faint)" }}>
              {engineOnline ? `v${status?.engine.version}` : "offline"}
            </div>
            <div className="faint" style={{ fontSize: 12 }}>
              {engineOnline
                ? `Java ${status?.engine.java_version} · up ${status?.engine.uptime_seconds}s`
                : "start: cd backend && mvn spring-boot:run"}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Analyzer service</div>
            <div className="stat-value" style={{ color: analyzerOnline ? "var(--ok)" : "var(--text-faint)" }}>
              {analyzerOnline ? `v${status?.analyzer.version ?? "?"}` : "offline"}
            </div>
            <div className="faint" style={{ fontSize: 12 }}>
              {analyzerOnline
                ? `model: ${status?.analyzer.model_loaded ? "ONNX + ensemble" : "statistical ensemble"}`
                : "start: cd ml-service && .venv/bin/uvicorn app.main:app --port 8000"}
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Carrier modules</div>
            <div className="stat-value">{status?.modules.length ?? 5}</div>
            <div className="faint" style={{ fontSize: 12 }}>
              image · audio · video · network · text
            </div>
          </div>
          <div className="stat">
            <div className="stat-label">Scans executed</div>
            <div className="stat-value">{status?.scans_executed ?? 0}</div>
            <div className="faint" style={{ fontSize: 12 }}>
              persisted in the engine H2 store
            </div>
          </div>
        </div>
      </section>

      <section className="section">
        <h2>Architecture</h2>
        <div className="card">
          <div className="flow">
            <span className="flow-node">
              <strong>Console</strong> · Next.js
            </span>
            <span className="flow-arrow">─▶</span>
            <span className="flow-node">
              <strong>Engine</strong> · Spring Boot (hide/extract/scan/clean/evolve)
            </span>
            <span className="flow-arrow">─▶</span>
            <span className="flow-node">
              <strong>Analyzer</strong> · FastAPI (RS · chi-square · plane stats)
            </span>
          </div>
          <p className="muted" style={{ marginBottom: 0, fontSize: 13.5 }}>
            Everything runs on your machine. The console proxies <code>/api/v1/*</code> to the
            engine; the engine calls the analyzer for scores and PDF reports. No telemetry, no
            outbound traffic, no cloud components.
          </p>
        </div>
      </section>

      <section className="section">
        <h2>Carrier modules</h2>
        <div className="card-grid">
          {(status?.modules ?? []).map((m) => (
            <div key={m.id} className="module-card">
              <div className="row spread">
                <h3>{m.display_name}</h3>
                <span className="module-kind">{m.media_kind}</span>
              </div>
              <p>{m.description}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="section">
        <h2>Recent scans</h2>
        <div className="card" style={{ padding: 0 }}>
          {scans.length === 0 ? (
            <p className="muted" style={{ padding: "16px 20px", margin: 0 }}>
              No scans recorded yet. Try the sample files in <code>samples/</code> on the{" "}
              <Link href="/scan">scan page</Link>.
            </p>
          ) : (
            <table className="data">
              <thead>
                <tr>
                  <th>File</th>
                  <th>Verdict</th>
                  <th>Threat</th>
                  <th>Kind</th>
                  <th>When</th>
                </tr>
              </thead>
              <tbody>
                {scans.map((s) => (
                  <tr key={s.scan_id}>
                    <td className="mono">{s.analysis.file.name}</td>
                    <td style={{ color: verdictColor(String(s.analysis.verdict)), fontFamily: "var(--mono)" }}>
                      {String(s.analysis.verdict)}
                    </td>
                    <td className="mono">{s.analysis.threat_score.toFixed(3)}</td>
                    <td className="mono">{s.analysis.file.kind}</td>
                    <td className="faint">{new Date(s.created_at).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      <footer className="footer">
        <span>StegoHX · authorized security research use only · see docs/SECURITY.md</span>
        <span className="mono">
          engine {engineOnline ? "online" : "offline"} · analyzer {analyzerOnline ? "online" : "offline"}
        </span>
      </footer>
    </div>
  );
}
