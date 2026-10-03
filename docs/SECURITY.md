# StegoHX Security & Scope Document

## What StegoHX is

A local, defensive steganography workbench: analyze media, study embedding
detectability, build detection corpora, and sanitize files. It is a research
and blue-team tool, comparable in spirit to published steganalysis toolkits.

## What StegoHX is not

StegoHX is **not** a trojan, RAT, implant, or covert-channel operator. It has
no remote-access functionality of any kind. Specifically, the codebase
contains:

- no C2/beacon/heartbeat logic, no reverse shells, no command execution
  endpoints (nothing in the codebase calls `Runtime.exec`, `ProcessBuilder`,
  or opens sockets other than loopback HTTP between its own three services
  and the targets the *user* configures);
- no covert data collection — the only persistence is scan metadata the user
  generates through the UI, in a local H2 database;
- no telemetry, update checks, or outbound network traffic;
- no obfuscated or undocumented behavior — every endpoint is specified in
  `docs/API.md` and every format in `docs/STEGO-SPEC.md`.

The `network_transport` module deserves a explicit note: it is a **pure
offline codec** that shapes payloads into the *textual form* of DNS/HTTP
covert-channel traffic for detection training. It opens no sockets and
generates no traffic.

## Authorized use

Use StegoHX only on systems and media you own or are explicitly authorized
to test (employer engagement scope, CTF/lab environments, your own research
corpora). Embedding payloads into media you then transmit may be unlawful in
many jurisdictions regardless of intent; detection research on
clearly-labeled synthetic data (like `samples/`) carries no such concern.

## Defensive value

The suite was designed blue-team-first:

- **Triage:** the scan pipeline produces verdicts with explicit
  `INCONCLUSIVE` semantics instead of overconfident binary answers, plus a
  per-analyzer breakdown you can audit.
- **Sanitization:** the clean operation irreversibly destroys LSB-plane and
  container-level payloads before media of unknown provenance is re-shared.
- **Detector hardening:** evolve quantifies how detectable an embedding
  strategy is against your own analyzer, and its outputs are directly usable
  as labeled pairs for training the pluggable ONNX model.
- **Reporting:** PDF reports create an audit trail for triage workflows.

## Attack-surface notes (for operators)

- Services bind to loopback by default. Do not expose them unauthenticated;
  they are analysis workbenches, not multi-tenant services.
- Uploads are held in memory (30 MB cap). The engine never writes covers or
  stego objects to disk; scan metadata (file name, size, mime, verdict,
  analyzer JSON) is persisted to a local H2 database at `backend/data/`.
- The payload keystream (HMAC-SHA256 XOR) is an obfuscation layer keyed by
  the passphrase — not authenticated encryption. Encrypt payloads first if
  confidentiality matters.
- CVE-style vulnerability reports: open a GitHub issue with reproduction
  details. Please do not test against deployments you do not own.

## Responsible disclosure of steganographic findings

If StegoHX's analyzer flags media in a real investigation, treat the result
as a *lead*, not evidence: statistical steganalysis is probabilistic, and
`INCONCLUSIVE` results must never be presented as proof. Confirm with
extraction attempts on preserved copies.
