"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { api, SystemStatus } from "@/lib/api";

const LINKS = [
  { href: "/", label: "Dashboard" },
  { href: "/scan", label: "Scan" },
  { href: "/workbench", label: "Workbench" },
  { href: "/docs", label: "Docs" },
];

export default function Nav() {
  const pathname = usePathname();
  const [status, setStatus] = useState<SystemStatus | null>(null);

  const refresh = useCallback(() => {
    api.systemStatus().then(setStatus).catch(() => setStatus(null));
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 15000);
    return () => clearInterval(timer);
  }, [refresh]);

  const engineOnline = status?.engine.version != null;
  const analyzerOnline = status?.analyzer.reachable === true;

  return (
    <header className="nav">
      <div className="nav-inner">
        <Link href="/" className="brand">
          <span className="brand-mark">◆</span> Stego<span className="brand-accent">HX</span>
        </Link>
        <nav className="nav-links">
          {LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className={`nav-link ${pathname === link.href ? "active" : ""}`}
            >
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="nav-status">
          <span className={`pill ${engineOnline ? "pill-ok" : "pill-off"}`}>
            engine {engineOnline ? `v${status?.engine.version}` : "offline"}
          </span>
          <span className={`pill ${analyzerOnline ? "pill-ok" : "pill-off"}`}>
            analyzer {analyzerOnline ? status?.analyzer.version ?? "on" : "offline"}
          </span>
        </div>
      </div>
    </header>
  );
}
