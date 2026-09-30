package com.fasterxml.jackson.core.sym;

import datadog.trace.util.Latch;

/**
 * Reads whether a {@link ByteQuadsCanonicalizer} interns its field names, from the package-private
 * {@code _interner} field.
 *
 * <p>A classpath that mixes Jackson builds can lack the field, which surfaces as a {@link
 * NoSuchFieldError}. That is the same for every canonicalizer, so a single {@link Latch} covers the
 * read: the first failure is rethrown, so the instrumentation exception handler still reports it
 * (at least once, bounded by concurrency, since threads racing the first failure each rethrow), and
 * afterwards the answer is {@code true} ("interned") without throwing. See {@code
 * JsonParser216Helper} for why "interned" is the default.
 */
public final class ByteQuadsCanonicalizer216Helper {
  private ByteQuadsCanonicalizer216Helper() {}

  private static final Latch<ByteQuadsCanonicalizer, Boolean, RuntimeException> INTERNER_LATCH =
      new Latch<ByteQuadsCanonicalizer, Boolean, RuntimeException>() {
        @Override
        protected Boolean get(ByteQuadsCanonicalizer symbols) {
          return handleNoSuchField(symbols, s -> s._interner != null);
        }
      };

  public static boolean fetchInterner(ByteQuadsCanonicalizer symbols) {
    return INTERNER_LATCH.tryGetOrDefault(symbols, Boolean.TRUE);
  }
}
