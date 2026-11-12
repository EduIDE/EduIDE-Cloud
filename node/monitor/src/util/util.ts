import * as vscode from 'vscode';

import { getFromEnv, THEIACLOUD_SESSION_SECRET } from '../env-variables';

/**
 * A prewarmed pod starts before its session exists, so the operator delivers the secret through the data bridge.
 */
async function getSessionSecret(): Promise<string | undefined> {
  const fromEnv = getFromEnv(THEIACLOUD_SESSION_SECRET);
  if (fromEnv) {
    return fromEnv;
  }
  try {
    const injected = await vscode.commands.executeCommand<Record<string, string>>('dataBridge.getEnv', [
      THEIACLOUD_SESSION_SECRET
    ]);
    return injected?.[THEIACLOUD_SESSION_SECRET];
  } catch {
    // Data bridge not installed or not active yet, so there is nothing to compare against.
    return undefined;
  }
}

export async function isAuthorized(req: any): Promise<boolean> {
  const bearerHeader = req.headers['authorization'];
  if (bearerHeader) {
    const splitBearer = bearerHeader.split(' ');
    if (splitBearer.length === 2) {
      const bearerToken = splitBearer[1];
      const sessionSecret = await getSessionSecret();
      // An unclaimed pod has no secret, and an empty token must not match that.
      if (bearerToken && bearerToken === sessionSecret) {
        return true;
      } else {
        console.debug('unauthorized');
      }
    }
  }
  return false;
}
