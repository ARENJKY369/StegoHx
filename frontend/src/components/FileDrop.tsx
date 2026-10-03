"use client";

import { DragEvent, useRef, useState } from "react";

interface Props {
  file: File | null;
  onFile: (file: File | null) => void;
  optional?: boolean;
  hint?: string;
}

export default function FileDrop({ file, onFile, optional, hint }: Props) {
  const [active, setActive] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setActive(false);
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) onFile(dropped);
  };

  return (
    <div
      className={`dropzone ${active ? "dropzone-active" : ""}`}
      onDragOver={(e) => {
        e.preventDefault();
        setActive(true);
      }}
      onDragLeave={() => setActive(false)}
      onDrop={onDrop}
      onClick={() => inputRef.current?.click()}
      role="button"
      tabIndex={0}
    >
      <input
        ref={inputRef}
        type="file"
        hidden
        onChange={(e) => onFile(e.target.files?.[0] ?? null)}
      />
      {file ? (
        <div className="dropzone-file">
          <span className="dropzone-name">{file.name}</span>
          <span className="dropzone-meta">
            {(file.size / 1024).toFixed(1)} KB · {file.type || "unknown type"}
          </span>
          <button
            className="btn btn-ghost btn-sm"
            onClick={(e) => {
              e.stopPropagation();
              onFile(null);
            }}
          >
            clear
          </button>
        </div>
      ) : (
        <div className="dropzone-empty">
          <span className="dropzone-icon">⇪</span>
          <span>{optional ? "Drop a cover file (optional)" : "Drop a file here or click to browse"}</span>
          {hint ? <span className="dropzone-hint">{hint}</span> : null}
        </div>
      )}
    </div>
  );
}
