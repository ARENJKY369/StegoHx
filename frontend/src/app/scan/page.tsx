"use client";

import { useCallback, useEffect, useState } from "react";
import FileDrop from "@/components/FileDrop";
import ScoreGauge from "@/components/ScoreGauge";
import { Bar, Callout, ErrorPanel } from "@/components/Ui";
import {
  ApiRequestError,
  ScanResponse,
  api,
  formatBytes,
  verdictColor,
} from "@/lib/api";

export default function ScanPage() {
  const [file, setFile] = useState<File | null>(null);
  const [scan, setScan] = useState<ScanResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [recent, setRecent] = useState<ScanResponse[]>([]);

  const loadRecent = useCallback(() => {
    api.recentScans(10).then(setRecent).catch(() => setRecent([]));
  }, []);

  useEffect(loadRecent, [loadRecent]);

  const runScan = async (target: File | null) => {
    if (!target) return;
    setBusy(true);
    setError(null);
    setHint(null);
    setScan(null);
    try {
      const result = await api.scan(target);
      setScan(result);
      loadRecent();
    } catch (e) {
      if (e instanceof ApiRequestError) {
        setError(e.message);
        setHint(e.detail.hint ?? null);
      } else {
        setError(String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const isPreviewScan = scan?.scan_id.startsWith("preview-") ?? false;
  const analysis = scan?.analysis;

  return (
    <div>
      <h1>Scan</h1>
      <p className="sub">
        Run the statistical steganalysis ensemble over a file: RS analysis, chi-square PoV attack
        and bit-plane coherence for images; quiet-segment statistics for WAV audio; container
        heuristics for MP4/MOV. Results include a verdict, per-analyzer breakdown and a
        recommended action.
      </p>

      <FileDrop
        file={file}
        onFile={(f) => {
          setFile(f);
          if (f) runScan(f);
        }}
        hint="PNG / JPEG / BMP / WAV / MP4 · max 30 MB · samples available in samples/"
      />

      <div style={{ height: 18 }} />

      {busy ? <div className="callout callout-info">Analyzing {file?.name}…</div> : null}
      {error ? <ErrorPanel title="Scan failed" message={error} hint={hint ?? undefined} /> : null}

      {analysis ? (
        <div className="scan-layout section" style={{ marginTop: 24 }}>
          <div>
            <div className="card" style={{ display: "flex", gap: 26, flexWrap: "wrap", alignItems: "center" }}>
              <ScoreGauge
                score={analysis.threat_score}
                verdict={String(analysis.verdict)}
                confidence={analysis.confidence}
              />
              <div style={{ flex: 1, minWidth: 260 }}>
                <dl className="kv">
                  <dt>file</dt>
                  <dd className="mono">{analysis.file.name}</dd>
                  <dt>size</dt>
                  <dd>
                    {formatBytes(analysis.file.size_bytes)}
                    {analysis.file.width ? ` · ${analysis.file.width}×${analysis.file.height}` : ""}
                  </dd>
                  <dt>mime / kind</dt>
                  <dd className="mono">
                    {analysis.file.mime} · {analysis.file.kind}
                  </dd>
                  <dt>recommended</dt>
                  <dd>
                    <strong style={{ fontFamily: "var(--mono)" }}>{String(analysis.recommended_action)}</strong>
                  </dd>
                  <dt>scoring</dt>
                  <dd className="muted">
                    {analysis.model_used} · analyzer v{analysis.analyzer_version} · {analysis.analysis_ms} ms
                  </dd>
                </dl>
              </div>
            </div>

            <div className="card" style={{ marginTop: 14 }}>
              <h3>Summary</h3>
              <p className="muted" style={{ margin: 0 }}>
                {analysis.summary}
              </p>
            </div>

            <div className="card" style={{ marginTop: 14, padding: 0 }}>
              <table className="data">
                <thead>
                  <tr>
                    <th>Analyzer</th>
                    <th>Score</th>
                    <th>Weight</th>
                    <th>Notes</th>
                  </tr>
                </thead>
                <tbody>
                  {analysis.analyzers.map((f) => (
                    <tr key={f.name} style={{ opacity: f.applicable ? 1 : 0.45 }}>
                      <td>
                        {f.display_name}
                        {!f.applicable ? <span className="faint"> (n/a)</span> : null}
                      </td>
                      <td>
                        <div className="row" style={{ gap: 8, flexWrap: "nowrap" }}>
                          <span
                            className="score-cell"
                            style={{ color: verdictColor(String(analysis.verdict)), minWidth: 44 }}
                          >
                            {f.score.toFixed(2)}
                          </span>
                          <Bar
                            value={f.score}
                            color={f.applicable ? verdictColor(String(analysis.verdict)) : "var(--surface-3)"}
                          />
                        </div>
                      </td>
                      <td className="mono">{f.weight.toFixed(2)}</td>
                      <td style={{ maxWidth: 420 }}>
                        {f.notes}
                        {f.applicable && f.details ? (
                          <details className="raw">
                            <summary>raw metrics</summary>
                            <pre className="code" style={{ marginTop: 8 }}>
                              {JSON.stringify(f.details, null, 2)}
                            </pre>
                          </details>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="row" style={{ marginTop: 16 }}>
              {isPreviewScan ? (
                <button className="btn" disabled title="PDF reports require the Java engine">
                  Download PDF report
                </button>
              ) : (
                <a className="btn btn-accent" href={api.reportUrl(scan.scan_id)} target="_blank" rel="noreferrer">
                  Download PDF report
                </a>
              )}
              <button className="btn" onClick={() => runScan(file)} disabled={!file || busy}>
                Re-scan
              </button>
            </div>

            {isPreviewScan ? (
              <Callout kind="warn" title="Preview mode">
                The Java engine is offline, so this scan was served directly by the analyzer
                service: history is not persisted and PDF generation is unavailable. Start the
                engine (<code>cd backend && mvn spring-boot:run</code>) for the full experience.
              </Callout>
            ) : null}
          </div>

          <aside>
            <div className="card">
              <h3>Recent scans</h3>
              {recent.length === 0 ? (
                <p className="faint" style={{ fontSize: 13 }}>
                  Nothing recorded yet.
                </p>
              ) : (
                <table className="data">
                  <tbody>
                    {recent.map((s) => (
                      <tr
                        key={s.scan_id}
                        className="click-row"
                        onClick={() => setScan(s)}
                        style={s.scan_id === scan.scan_id ? { background: "var(--bg-raise)" } : undefined}
                      >
                        <td>
                          <div className="mono" style={{ fontSize: 12.5 }}>
                            {s.analysis.file.name}
                          </div>
                          <div className="row" style={{ gap: 8 }}>
                            <span
                              className="mono"
                              style={{
                                fontSize: 12,
                                color: verdictColor(String(s.analysis.verdict)),
                              }}
                            >
                              {String(s.analysis.verdict)}
                            </span>
                            <span className="faint mono" style={{ fontSize: 12 }}>
                              {s.analysis.threat_score.toFixed(3)}
                            </span>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div className="card" style={{ marginTop: 14 }}>
              <h3>Verdict scale</h3>
              <table className="data">
                <tbody>
                  <tr>
                    <td className="mono" style={{ color: "var(--ok)" }}>CLEAN</td>
                    <td className="faint">threat &lt; 0.20</td>
                  </tr>
                  <tr>
                    <td className="mono" style={{ color: "var(--warn)" }}>INCONCLUSIVE</td>
                    <td className="faint">0.20 – 0.45</td>
                  </tr>
                  <tr>
                    <td className="mono" style={{ color: "var(--warn-high)" }}>LIKELY_STEGO</td>
                    <td className="faint">0.45 – 0.70</td>
                  </tr>
                  <tr>
                    <td className="mono" style={{ color: "var(--danger)" }}>HIGH_CONFIDENCE_STEGO</td>
                    <td className="faint">≥ 0.70</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </aside>
        </div>
      ) : null}
    </div>
  );
}
