import * as assert from 'node:assert/strict';
import { test } from 'node:test';

import { isUserDocument, isUserEdit } from './user-activity';

const doc = (scheme: string): { uri: { scheme: string } } => ({ uri: { scheme } });

test('edits in workspace files and untitled editors count as activity', () => {
  assert.equal(isUserEdit({ document: doc('file'), contentChanges: [{}] }), true);
  assert.equal(isUserEdit({ document: doc('untitled'), contentChanges: [{}] }), true);
});

test('output channel appends do not count as activity', () => {
  // The Artemis extension appends to its output channel every 5 seconds and kept idle sessions alive.
  assert.equal(isUserEdit({ document: doc('output'), contentChanges: [{}] }), false);
});

test('settings storage and git documents do not count as activity', () => {
  assert.equal(isUserEdit({ document: doc('user-storage'), contentChanges: [{}] }), false);
  assert.equal(isUserEdit({ document: doc('git'), contentChanges: [{}] }), false);
  assert.equal(isUserDocument(doc('user-storage')), false);
});

test('a change event without content changes does not count as activity', () => {
  assert.equal(isUserEdit({ document: doc('file'), contentChanges: [] }), false);
});
