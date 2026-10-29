import { defineConfig } from "vite"
import { svelte } from "@sveltejs/vite-plugin-svelte"

// The built app is served by PocketBase from pb_public (see server/Dockerfile).
// In development, API calls go to a local PocketBase on :8090.
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: { "/api": { target: process.env.PB_URL ?? "http://127.0.0.1:8090", ws: true } },
    // shared/ (icons used by the app too) sits next to web/.
    fs: { allow: [".."] },
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: { include: ["tests/**/*.test.ts"] },
})
