import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // "@/..." means "src/...". shadcn/ui's generated components import through
    // this alias, and it keeps our own imports from turning into ../../../.
    // tsconfig.app.json declares the same mapping so the editor agrees.
    alias: { "@": path.resolve(import.meta.dirname, "./src") },
  },
  server: {
    // The API runs on 8080 and this dev server on 5173, so every request is
    // cross-origin. The API allows this exact origin by default
    // (invoicely.security.allowed-origins) — a proxy here would hide that
    // configuration during development and let it break unnoticed in
    // production, where the two really are on different origins.
    port: 5173,
  },
});
