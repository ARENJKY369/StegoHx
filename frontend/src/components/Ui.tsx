"use client";

import { ReactNode } from "react";

export function Callout({
  kind = "info",
  title,
  children,
}: {
  kind?: "info" | "warn" | "error" | "ok";
  title?: string;
  children: ReactNode;
}) {
  return (
    <div className={`callout callout-${kind}`}>
      {title ? <div className="callout-title">{title}</div> : null}
      <div className="callout-body">{children}</div>
    </div>
  );
}

export function Bar({ value, color }: { value: number; color?: string }) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div className="bar">
      <div className="bar-fill" style={{ width: `${pct}%`, background: color ?? "var(--accent)" }} />
    </div>
  );
}

export function ErrorPanel({ title, message, hint }: { title: string; message: string; hint?: string }) {
  return (
    <div className="callout callout-error">
      <div className="callout-title">{title}</div>
      <div className="callout-body">
        <p>{message}</p>
        {hint ? <p className="muted mono">{hint}</p> : null}
      </div>
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint ? <span className="field-hint">{hint}</span> : null}
    </label>
  );
}

export function DownloadButton({
  base64,
  mime,
  filename,
  label,
  disabled,
}: {
  base64: string;
  mime: string;
  filename: string;
  label: string;
  disabled?: boolean;
}) {
  if (disabled) {
    return (
      <button className="btn" disabled title="Requires the Java engine">
        {label}
      </button>
    );
  }
  return (
    <a
      className="btn btn-accent"
      href={`data:${mime};base64,${base64}`}
      download={filename}
    >
      {label}
    </a>
  );
}
