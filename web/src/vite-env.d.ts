/// <reference types="vite/client" />

/**
 * Types for the environment variables this app reads, so a typo in
 * `import.meta.env.VITE_API_BSAE_URL` is a compile error rather than an
 * undefined at runtime.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
