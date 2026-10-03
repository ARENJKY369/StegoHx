/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // The console never talks to services directly from the browser: every
  // /api/v1/* request is handled by the app's own route handler, which
  // proxies to the Java engine (and falls back to the analyzer service for
  // scans when the engine is not running - see src/app/api/v1/[...path]/route.ts).
};

export default nextConfig;
