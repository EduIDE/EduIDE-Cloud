import { createHash, timingSafeEqual } from 'crypto';

const digest = (value: string): Buffer => createHash('sha256').update(value).digest();

/**
 * Compares a presented secret with the expected one without leaking where they differ. Hashing first gives
 * `timingSafeEqual` inputs of equal length, so the length of the secret does not leak either.
 * An empty or missing value never matches.
 */
export function secretsMatch(presented: string | undefined, expected: string | undefined): boolean {
  if (!presented || !expected) {
    return false;
  }
  return timingSafeEqual(digest(presented), digest(expected));
}
