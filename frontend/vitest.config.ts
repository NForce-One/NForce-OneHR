import { defineConfig } from 'vitest/config'

// Separate from vite.config.ts on purpose — this repo has no test runner set up yet, so this
// file only ever affects `npm test`, never the dev server or the production build (which keep
// using vite.config.ts, untouched).
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
