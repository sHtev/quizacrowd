import { defineConfig } from '@playwright/test'

// Runs against the local k3d cluster (scripts/local-up.sh), through Traefik on localhost:8080.
// Chromium resolves *.localhost to 127.0.0.1 itself, so no /etc/hosts entries are needed.
export default defineConfig({
  testDir: 'tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: { baseURL: 'http://redlion.localhost:8080', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
