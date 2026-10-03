"use client";

import { verdictColor } from "@/lib/api";

interface Props {
  score: number; // 0..1
  verdict: string;
  confidence: number;
}

/** Semi-circular threat gauge. */
export default function ScoreGauge({ score, verdict, confidence }: Props) {
  const radius = 80;
  const circumference = Math.PI * radius; // half circle
  const clamped = Math.max(0, Math.min(1, score));
  const offset = circumference * (1 - clamped);
  const color = verdictColor(verdict);
  const label = (clamped * 100).toFixed(1);

  return (
    <div className="gauge">
      <svg viewBox="0 0 200 110" width="220" height="121">
        <path
          d="M 20 100 A 80 80 0 0 1 180 100"
          fill="none"
          stroke="var(--surface-2)"
          strokeWidth="14"
          strokeLinecap="round"
        />
        <path
          d="M 20 100 A 80 80 0 0 1 180 100"
          fill="none"
          stroke={color}
          strokeWidth="14"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
        />
        <text x="100" y="82" textAnchor="middle" className="gauge-value" fill={color}>
          {label}
        </text>
        <text x="100" y="102" textAnchor="middle" className="gauge-unit">
          / 100
        </text>
      </svg>
      <div className="gauge-caption">
        <span className="verdict-badge" style={{ borderColor: color, color }}>
          {verdict}
        </span>
        <span className="gauge-confidence">confidence {(confidence * 100).toFixed(0)}%</span>
      </div>
    </div>
  );
}
