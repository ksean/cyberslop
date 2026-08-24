# Product Specification

## Vision

Cyberslop is a dystopian side-scrolling adventure game. Its world, narrative, and mechanics will be specified incrementally.

## Runtime

- **PROD-001:** A player must need only a modern web browser to play a deployed build.
- **PROD-002:** The game must execute client-side as a Kotlin/WebAssembly application; a player must not need to install a native application, runtime, or browser extension.
- **PROD-003:** Supported browsers must provide WebAssembly garbage collection. The initial compatibility baseline is Chrome or Chromium 119+, Firefox 120+, and Safari 18.2+.
- **PROD-004:** Player-facing controls must be usable with a keyboard and expose accessible names to browser assistive technology.

## Initial experience

- **PROD-010:** Opening the game URL must present the title-screen behavior defined by [change 0001](changes/0001-title-screen.md).
- **PROD-011:** Starting and continuing gameplay are outside the initial title-screen slice and must be specified before implementation.
