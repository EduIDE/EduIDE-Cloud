import * as assert from 'node:assert';
import { test } from 'node:test';

import { secretsMatch } from './secret-compare';

test('equal secrets match', () => {
  assert.strictEqual(secretsMatch('s3cret', 's3cret'), true);
});

test('different secrets of equal and different length do not match', () => {
  assert.strictEqual(secretsMatch('s3cret', 's3creT'), false);
  assert.strictEqual(secretsMatch('s3cret', 's3cret-longer'), false);
});

test('empty or missing values never match', () => {
  assert.strictEqual(secretsMatch('', ''), false);
  assert.strictEqual(secretsMatch('x', undefined), false);
  assert.strictEqual(secretsMatch(undefined, undefined), false);
});
