import { defineConfig, devices } from '@playwright/test'

const appUrl = process.env.DICE_APP_URL
const baseURL = appUrl ?? 'http://127.0.0.1:4173'

export default defineConfig({
  testDir: './e2e',
  use: { baseURL, ...devices['Desktop Chrome'], actionTimeout: 5000 },
  webServer: appUrl ? undefined : {
    command: 'bun run dev --host 127.0.0.1 --port 4173 --strictPort',
    url: baseURL,
    reuseExistingServer: false,
  },
})
