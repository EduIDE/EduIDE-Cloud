# Subtask 5: Extension Redesign (EduIDE-lsp-extension)

## Objective

Replace the hardcoded Java/Rust language client config with dynamic discovery from the `SIDECAR_CONFIG` env var.

## Current State

**`src/extension.ts`** hardcodes:
- `languageServerConfigs` map with `java` and `rust` entries
- Activation events: `onLanguage:rust`, `onLanguage:java`
- File extension mapping in `getFileExtensions()`
- Env var names: `LS_JAVA_HOST`, `LS_RUST_HOST`, etc.

## New Design

### Core change: Read `SIDECAR_CONFIG` at activation

```typescript
interface SidecarEntry {
  name: string;
  host: string;
  port: number;
  languages: string[];  // e.g. ["java"] or ["rust"]
}

function getSidecarConfig(): SidecarEntry[] {
  const raw = process.env.SIDECAR_CONFIG;
  if (!raw) return [];
  try {
    return JSON.parse(raw);
  } catch (e) {
    console.error('[SIDECAR] Failed to parse SIDECAR_CONFIG:', e);
    return [];
  }
}
```

### Activation strategy

Change activation from specific `onLanguage:*` to `onStartupFinished`:

**`package.json`**:
```json
{
  "activationEvents": ["onStartupFinished"]
}
```

On activation, parse `SIDECAR_CONFIG` and register `onDidOpenTextDocument` listener that lazily starts clients per language:

```typescript
export function activate(context: ExtensionContext) {
  const sidecars = getSidecarConfig();
  const lsSidecars = sidecars.filter(s => s.languages && s.languages.length > 0);

  if (lsSidecars.length === 0) {
    // Fallback: try legacy env vars
    tryLegacyActivation(context);
    return;
  }

  // Build language → sidecar mapping
  const languageMap = new Map<string, SidecarEntry>();
  for (const sidecar of lsSidecars) {
    for (const lang of sidecar.languages) {
      languageMap.set(lang, sidecar);
    }
  }

  // Lazy connect on file open
  context.subscriptions.push(
    workspace.onDidOpenTextDocument(doc => {
      const entry = languageMap.get(doc.languageId);
      if (entry && !clients.has(doc.languageId)) {
        startClient(doc.languageId, entry, context);
      }
    })
  );

  // Check already-open documents
  workspace.textDocuments.forEach(doc => {
    const entry = languageMap.get(doc.languageId);
    if (entry && !clients.has(doc.languageId)) {
      startClient(doc.languageId, entry, context);
    }
  });
}
```

### TCP connection with retry

The sidecar pod may not be Ready when Theia starts. Add exponential backoff:

```typescript
function connectWithRetry(host: string, port: number, maxRetries = 10): Promise<net.Socket> {
  return new Promise((resolve, reject) => {
    let attempt = 0;
    function tryConnect() {
      attempt++;
      const socket = net.connect({ host, port });
      socket.on('connect', () => resolve(socket));
      socket.on('error', (err) => {
        if (attempt >= maxRetries) {
          reject(new Error(`Failed after ${maxRetries} attempts: ${err.message}`));
          return;
        }
        const delay = Math.min(1000 * Math.pow(2, attempt - 1), 30000);
        console.log(`[SIDECAR] Connection attempt ${attempt} failed, retrying in ${delay}ms...`);
        setTimeout(tryConnect, delay);
      });
    }
    tryConnect();
  });
}
```

### Legacy fallback

For backward compat with old operator versions that still inject `LS_JAVA_HOST` etc.:

```typescript
function tryLegacyActivation(context: ExtensionContext) {
  const legacyConfigs: Record<string, { hostEnv: string; portEnv: string }> = {
    java: { hostEnv: 'LS_JAVA_HOST', portEnv: 'LS_JAVA_PORT' },
    rust: { hostEnv: 'LS_RUST_HOST', portEnv: 'LS_RUST_PORT' },
  };

  for (const [lang, cfg] of Object.entries(legacyConfigs)) {
    const host = process.env[cfg.hostEnv] || process.env['LS_HOST'];
    const port = process.env[cfg.portEnv] || process.env['LS_PORT'];
    if (host && port) {
      // Register lazy client for this language
    }
  }
}
```

### Version bump

Update `package.json` version to `0.1.0` (breaking change in activation model). Publish new VSIX to Open VSX.

## Files to Modify

| Action | File | Repo |
|--------|------|------|
| REWRITE | `src/extension.ts` | EduIDE-lsp-extension |
| MODIFY | `package.json` (activation events, version) | EduIDE-lsp-extension |

### Downstream: Update `no-ls` image patches

After publishing the new VSIX, update the plugin reference in the IDE image:

| Action | File | Repo |
|--------|------|------|
| MODIFY | `images/java-17-no-ls/package.json.patch` | EduIDE |
| MODIFY | `images/rust-no-ls/package.json.patch` | EduIDE |
| MODIFY | `images/theia-no-ls/package.json.patch` (if exists) | EduIDE |

Update the VSIX URL to point to the new version:
```json
{
  "theiaPlugins": {
    "theia-lsp": "https://open-vsx.org/api/nikolashack/theia-lsp/0.1.0/file/nikolashack.theia-lsp-0.1.0.vsix"
  }
}
```

## Validation

- Local test: set `SIDECAR_CONFIG` env var, run extension in dev mode, open Java file → verify connection
- Local test: unset `SIDECAR_CONFIG`, set `LS_JAVA_HOST` → verify legacy fallback works
- Local test: set `SIDECAR_CONFIG` with 2 sidecars (java + rust) → verify both connect independently
- Docker test: run `docker-compose-java-only.yml` with updated extension and new env vars
