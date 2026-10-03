import type { Metadata } from "next";
import Nav from "@/components/Nav";
import "./globals.css";

export const metadata: Metadata = {
  title: "StegoHX Console",
  description:
    "Steganography analysis and research console - hide, extract, scan, clean and evolve operations over a local engine and analyzer service.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Nav />
        <main className="shell">{children}</main>
      </body>
    </html>
  );
}
