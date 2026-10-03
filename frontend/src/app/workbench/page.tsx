"use client";

import { useEffect, useState } from "react";
import FileDrop from "@/components/FileDrop";
import { Callout, ErrorPanel, Field } from "@/components/Ui";
import {
  ApiRequestError,
  CleanResponse,
  EvolveResponse,
  ExtractResponse,
  HideResponse,
  ModuleInfo,
  SystemStatus,
  api,
  base64ToBlob,
} from "@/lib/api";

type Tab = "hide" | "extract" | "clean" | "evolve";

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export default function Workbench() {
  const [tab, setTab] = useState<Tab>("hide");
  const [modules, setModules] = useState<ModuleInfo[]>([]);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [payload, setPayload] = useState("meet at 22:00, dock 7");
  const [key, setKey] = useState("");
  const [moduleId, setModuleId] = useState("");
  const [bitDepth, setBitDepth] = useState(1);
  const [spread, setSpread] = useState("SEQUENTIAL");
  const [seed, setSeed] = useState(0);
  const [variant, setVariant] = useState("dns");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const [hideResult, setHideResult] = useState<HideResponse | null>(null);
  const [extractResult, setExtractResult] = useState<ExtractResponse | null>(null);
  const [cleanResult, setCleanResult] = useState<CleanResponse | null>(null);
  const [evolveResult, setEvolveResult] = useState<EvolveResponse | null>(null);

  useEffect(() => {
    api
      .systemStatus()
      .then((s) => {
        setStatus(s);
        setModules(s.modules);
      })
      .catch(() => setModules([]));
  }, []);

  const engineOnline = status?.engine.version != null;

  const reset = () => {
    setError(null);
    setHint(null);
    setHideResult(null);
    setExtractResult(null);
    setCleanResult(null);
    setEvolveResult(null);
  };

  const guard = (needsFile = true, needsPayload = false): boolean => {
    if (needsFile && !file) {
      setError("Select a file first.");
      return false;
    }
    if (needsPayload && !payload.trim()) {
      setError("Enter a payload.");
      return false;
    }
    return true;
  };

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    reset();
    try {
      await fn();
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

  const doHide = () =>
    run(async () => {
      if (!guard(false, true)) return;
      const form = new FormData();
      if (file) form.append("file", file);
      form.append("payload", payload);
      form.append("key", key);
      if (moduleId) form.append("module_id", moduleId);
      form.append("bit_depth", String(bitDepth));
      form.append("spread", spread);
      form.append("seed", String(seed));
      form.append("variant", variant);
      setHideResult(await api.hide(form));
    });

  const doExtract = () =>
    run(async () => {
      if (!guard(true)) return;
      const form = new FormData();
      form.append("file", file!);
      form.append("key", key);
      if (moduleId) form.append("module_id", moduleId);
      form.append("seed", String(seed));
      setExtractResult(await api.extract(form));
    });

  const doClean = () =>
    run(async () => {
      if (!guard(true)) return;
      const form = new FormData();
      form.append("file", file!);
      if (moduleId) form.append("module_id", moduleId);
      setCleanResult(await api.clean(form));
    });

  const doEvolve = () =>
    run(async () => {
      if (!guard(true, true)) return;
      const form = new FormData();
      form.append("file", file!);
      form.append("payload", payload);
      form.append("key", key);
      if (moduleId) form.append("module_id", moduleId);
      setEvolveResult(await api.evolve(form));
    });

  const moduleOptions = (
    <>
      <option value="">auto-detect</option>
      {modules.map((m) => (
        <option key={m.id} value={m.id}>
          {m.display_name}
        </option>
      ))}
    </>
  );

  return (
    <div>
      <h1>Workbench</h1>
      <p className="sub">
        Synchronous stego operations against the local engine: hide a keyed payload into a carrier,
        extract it back, sanitize suspicious files, or let the evolve loop pick the embedding
        strategy with the lowest analyzer score.
      </p>

      {!engineOnline ? (
        <Callout kind="warn" title="Java engine offline">
          Hide / extract / clean / evolve are implemented by the Spring Boot engine, which is not
          running in this preview. Start it with <code>cd backend && mvn spring-boot:run</code>{" "}
          (requires JDK 17 + the analyzer service). The scan page remains fully functional here via
          the analyzer fallback.
        </Callout>
      ) : null}

      <div className="tabs">
        {(["hide", "extract", "clean", "evolve"] as Tab[]).map((t) => (
          <button key={t} className={`tab ${tab === t ? "active" : ""}`} onClick={() => { setTab(t); reset(); }}>
            {t === "hide" ? "Hide" : t === "extract" ? "Extract" : t === "clean" ? "Clean" : "Evolve"}
          </button>
        ))}
      </div>

      <div className="scan-layout">
        <div className="card">
          <FileDrop file={file} onFile={(f) => { setFile(f); reset(); }} optional={tab === "hide"} />

          {tab === "hide" || tab === "evolve" ? (
            <div style={{ marginTop: 16 }}>
              <Field label="Payload (text)" hint="embedded as UTF-8 bytes inside the keyed container">
                <textarea value={payload} onChange={(e) => setPayload(e.target.value)} />
              </Field>
            </div>
          ) : null}

          <div className="form-grid" style={{ marginTop: 6 }}>
            <Field label="Key (passphrase)">
              <input type="password" value={key} onChange={(e) => setKey(e.target.value)} placeholder="empty = default key" />
            </Field>
            <Field label="Module">
              <select value={moduleId} onChange={(e) => setModuleId(e.target.value)}>
                {moduleOptions}
              </select>
            </Field>
            {tab === "hide" ? (
              <>
                <Field label="Bit depth" hint="1-2 bits per carrier (image/audio modules)">
                  <select value={bitDepth} onChange={(e) => setBitDepth(Number(e.target.value))}>
                    <option value={1}>1 bit (LSB)</option>
                    <option value={2}>2 bits</option>
                  </select>
                </Field>
                <Field label="Spread">
                  <select value={spread} onChange={(e) => setSpread(e.target.value)}>
                    <option value="SEQUENTIAL">sequential</option>
                    <option value="SEEDED">seeded (keyed scatter)</option>
                  </select>
                </Field>
                <Field label="Seed (optional)" hint="non-zero overrides the key-derived seed; re-supply on extract">
                  <input type="number" value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
                </Field>
                <Field label="Network variant" hint="network module only">
                  <select value={variant} onChange={(e) => setVariant(e.target.value)}>
                    <option value="dns">DNS query labels</option>
                    <option value="http">HTTP header fragments</option>
                  </select>
                </Field>
              </>
            ) : null}
            {tab === "extract" ? (
              <Field label="Seed (if used at hide time)">
                <input type="number" value={seed} onChange={(e) => setSeed(Number(e.target.value))} />
              </Field>
            ) : null}
          </div>

          <div className="row" style={{ marginTop: 10 }}>
            <button
              className="btn btn-accent"
              onClick={tab === "hide" ? doHide : tab === "extract" ? doExtract : tab === "clean" ? doClean : doEvolve}
              disabled={busy}
            >
              {busy ? "Working…" : tab === "hide" ? "Hide payload" : tab === "extract" ? "Extract payload" : tab === "clean" ? "Sanitize file" : "Run evolve"}
            </button>
          </div>

          {error ? (
            <div style={{ marginTop: 8 }}>
              <ErrorPanel title="Operation failed" message={error} hint={hint ?? undefined} />
            </div>
          ) : null}

          {hideResult ? (
            <div className="section" style={{ marginTop: 18 }}>
              <Callout kind="ok" title={`Embedded via ${hideResult.module_name}`}>
                payload {hideResult.payload_bytes} B · capacity used{" "}
                {(hideResult.capacity_used_ratio * 100).toFixed(1)}% · output {hideResult.output_mime}
              </Callout>
              <button
                className="btn"
                onClick={() =>
                  downloadBlob(base64ToBlob(hideResult.output_base64, hideResult.output_mime), hideResult.output_name)
                }
              >
                Download {hideResult.output_name}
              </button>
            </div>
          ) : null}

          {extractResult ? (
            <div className="section" style={{ marginTop: 18 }}>
              <Callout kind="ok" title={`Payload recovered via ${extractResult.module_name}`}>
                {extractResult.payload_bytes} bytes · container verified:{" "}
                {String(extractResult.container_verified)}
              </Callout>
              {extractResult.payload_text ? (
                <pre className="code">{extractResult.payload_text}</pre>
              ) : (
                <p className="muted">
                  Binary payload ({extractResult.payload_bytes} B) - base64:
                </p>
              )}
              {!extractResult.payload_text ? (
                <pre className="code" style={{ maxHeight: 140, overflow: "auto" }}>
                  {extractResult.payload_base64}
                </pre>
              ) : null}
            </div>
          ) : null}

          {cleanResult ? (
            <div className="section" style={{ marginTop: 18 }}>
              <Callout kind="ok" title={`Sanitized via ${cleanResult.module_name}`}>
                {cleanResult.note}
              </Callout>
              <button
                className="btn"
                onClick={() => downloadBlob(base64ToBlob(cleanResult.output_base64, cleanResult.output_mime), "sanitized." + cleanResult.output_mime.split("/")[1])}
              >
                Download sanitized file
              </button>
            </div>
          ) : null}

          {evolveResult ? (
            <div className="section" style={{ marginTop: 18 }}>
              <Callout kind="ok" title="Evolve complete">
                baseline {evolveResult.baseline_threat_score.toFixed(3)} → selected{" "}
                {evolveResult.selected ? evolveResult.selected.threat_score.toFixed(3) : "n/a"}
              </Callout>
              <table className="data" style={{ marginTop: 10 }}>
                <thead>
                  <tr>
                    <th>Strategy</th>
                    <th>OK</th>
                    <th>Analyzer score</th>
                    <th>Capacity used</th>
                  </tr>
                </thead>
                <tbody>
                  {evolveResult.candidates.map((c) => (
                    <tr key={c.strategy.label}>
                      <td className="mono">
                        {c.strategy.label}
                        {evolveResult.selected?.strategy.label === c.strategy.label ? " ★" : ""}
                      </td>
                      <td>{c.ok ? "✓" : <span className="faint">{c.error}</span>}</td>
                      <td className="mono">{c.ok ? c.threat_score.toFixed(3) : "-"}</td>
                      <td className="mono">{(c.capacity_used * 100).toFixed(1)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="muted" style={{ marginTop: 10 }}>
                {evolveResult.advice}
              </p>
            </div>
          ) : null}
        </div>

        <aside>
          <div className="card">
            <h3>How it works</h3>
            {tab === "hide" ? (
              <p className="muted" style={{ fontSize: 13 }}>
                Payloads are wrapped in a keyed <code>STGX</code> container (HMAC-SHA256 keystream),
                then written into the carrier. Image/audio carriers get a 96-bit self-describing
                header so extraction only needs the key.
              </p>
            ) : null}
            {tab === "extract" ? (
              <p className="muted" style={{ fontSize: 13 }}>
                The module is auto-detected from the file. Extraction verifies the container magic
                and decodes the keystream with your key - wrong keys yield garbage or a clean
                error.
              </p>
            ) : null}
            {tab === "clean" ? (
              <p className="muted" style={{ fontSize: 13 }}>
                Sanitization destroys hidden data irreversibly: LSB planes are zeroed (image,
                audio) or payload atoms are stripped (MP4). Use before re-sharing media of unknown
                provenance.
              </p>
            ) : null}
            {tab === "evolve" ? (
              <p className="muted" style={{ fontSize: 13 }}>
                Each candidate strategy is embedded in memory and scored by the analyzer; the
                lowest-scoring strategy wins. This loop quantifies how detectable your own
                embedding choices are - the core workflow for hardening detectors.
              </p>
            ) : null}
          </div>
        </aside>
      </div>
    </div>
  );
}
