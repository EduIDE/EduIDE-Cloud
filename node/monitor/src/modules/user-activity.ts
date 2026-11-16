// Kept free of the vscode import so the rules can be tested without an extension host.

/**
 * Schemes of documents a user edits in an editor. Everything else (output channels, settings storage, git,
 * extension-owned views) is written by extensions on their own, e.g. the Artemis extension logs every 5 seconds.
 */
const USER_DOCUMENT_SCHEMES = ['file', 'untitled'];

export function isUserDocument(document: { uri: { scheme: string } }): boolean {
  return USER_DOCUMENT_SCHEMES.includes(document.uri.scheme);
}

export function isUserEdit(event: { document: { uri: { scheme: string } }; contentChanges: readonly unknown[] }): boolean {
  // An empty change list is a dirty-state or metadata change, not typing.
  return event.contentChanges.length > 0 && isUserDocument(event.document);
}
