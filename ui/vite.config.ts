import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/list-apps": "http://localhost:8080",
      "/apps": "http://localhost:8080",
      "/run_sse": "http://localhost:8080",
    },
  },
});
