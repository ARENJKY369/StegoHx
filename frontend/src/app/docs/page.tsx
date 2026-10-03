export const metadata = { title: "Docs · StegoHX Console" };

export default function DocsPage() {
  return (
    <div>
      <h1>Docs</h1>
      <p className="sub">
        Console-side summary of the StegoHX stack. Full specifications live in the repository:
        <code> docs/ARCHITECTURE.md</code>, <code>docs/API.md</code>, <code>docs/STEGO-SPEC.md</code>{" "}
        and <code>docs/SECURITY.md</code>.
      </p>

      <section className="section">
        <h2>Component map</h2>
        <pre className="code">{`┌────────────────────┐     /api/v1/* (same-origin proxy)     ┌─────────────────────────┐
│  Console (Next.js) │ ──────────────────────────────────▶ │  Engine (Spring Boot)    │
│  this UI           │                                     │  :8080                   │
└────────────────────┘                                     │  hide / extract / clean  │
                                                           │  scan / evolve           │
                                                           │  PDF reports (OpenPDF)   │
                                                           └───────────┬─────────────┘
                                                                       │ HTTP (localhost)
                                                                       ▼
                                                           ┌─────────────────────────┐
                                                           │  Analyzer (FastAPI)      │
                                                           │  :8000                   │
                                                           │  RS · chi-square · planes│
                                                           │  audio · container · ONNX│
                                                           └─────────────────────────┘`}</pre>
      </section>

      <section className="section">
        <h2>API surface</h2>
        <div className="card" style={{ padding: 0 }}>
          <table className="data">
            <thead>
              <tr>
                <th>Endpoint</th>
                <th>Method</th>
                <th>Purpose</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td className="mono">/api/v1/system/status</td>
                <td className="mono">GET</td>
                <td>engine/analyzer health, module registry, scan count</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/stego/hide</td>
                <td className="mono">POST</td>
                <td>embed a keyed payload (multipart: file?, payload, key, bit_depth, spread, seed, variant)</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/stego/extract</td>
                <td className="mono">POST</td>
                <td>recover a payload (multipart: file, key, seed?)</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/stego/clean</td>
                <td className="mono">POST</td>
                <td>sanitize a carrier (zero LSB planes / strip payload atoms)</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/stego/evolve</td>
                <td className="mono">POST</td>
                <td>adaptive embedding: score candidate strategies, pick the least detectable</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/scan</td>
                <td className="mono">POST</td>
                <td>full steganalysis of a file; persists a scan record</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/scans</td>
                <td className="mono">GET</td>
                <td>recent scan history</td>
              </tr>
              <tr>
                <td className="mono">/api/v1/scans/&#123;id&#125;/report.pdf</td>
                <td className="mono">GET</td>
                <td>professional PDF report of a scan</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section className="section">
        <h2>Evolve: adaptive embedding</h2>
        <p className="muted">
          The evolve loop is the bridge between the embedding engine and the analyzer: for a cover
          and payload, it evaluates the strategy space (bit depth 1-2 × sequential/seeded placement
          for LSB modules), asks the analyzer to score every candidate stego object, and selects
          the strategy with the lowest threat score. Used honestly, this is the standard
          red-team/blue-team workflow for measuring - and then improving - detector sensitivity.
        </p>
      </section>

      <section className="section">
        <h2>Scope &amp; ethics</h2>
        <p className="muted">
          StegoHX is a defensive and research tool. It is designed for analyzing media you own or
          are authorized to assess, for building detection corpora, and for studying steganographic
          robustness. It contains <strong>no covert functionality</strong>: every capability is
          documented here and in the repository docs, all services bind to localhost by default,
          and nothing phones home. See <code>docs/SECURITY.md</code> for the full authorized-use
          statement.
        </p>
      </section>
    </div>
  );
}
