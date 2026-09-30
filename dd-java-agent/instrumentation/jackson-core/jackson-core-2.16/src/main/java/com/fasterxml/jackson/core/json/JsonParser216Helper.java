package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer;
import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer216Helper;
import datadog.trace.util.Latch;

/**
 * Reads whether a {@link UTF8StreamJsonParser} interns its field names.
 *
 * <p>This reads package-private Jackson fields ({@code _symbols}, {@code _interner}). Stock
 * jackson-core 2.16+ always has them, but a classpath that mixes Jackson builds can lack them,
 * which surfaces as a {@link NoSuchFieldError}.
 *
 * <p><b>IAST design note:</b> when the fields are missing we cannot tell whether names are
 * interned, and we answer {@code true} ("interned"). The caller then records the current field name
 * but does <em>not</em> taint the name string. This is a deliberate trade-off:
 *
 * <ul>
 *   <li>Jackson interns field names by default, so "interned" is the likely answer.
 *   <li>Tainting an interned {@code String} taints the one shared instance, so every occurrence of
 *       that name (across requests) would look tainted. That is a false-positive risk.
 *   <li>The cost is a possible false negative: on such classpaths, an attacker-controlled JSON
 *       <em>key</em> reaching a sink is not detected. Values are still attributed to their field
 *       name.
 * </ul>
 *
 * <p>Each field read has its own {@link Latch}, here for {@code _symbols} and in {@link
 * ByteQuadsCanonicalizer216Helper} for {@code _interner}, so a classpath missing only one of them
 * keeps using the other. The first failure of each is rethrown so the instrumentation exception
 * handler still reports it once. After that the failure is remembered and calls return {@code true}
 * without throwing, so a broken classpath does not cost an exception per parsed field name.
 */
public final class JsonParser216Helper {
  private JsonParser216Helper() {}

  private static final Latch<UTF8StreamJsonParser, ByteQuadsCanonicalizer, RuntimeException>
      SYMBOLS =
          new Latch<UTF8StreamJsonParser, ByteQuadsCanonicalizer, RuntimeException>() {
            @Override
            protected ByteQuadsCanonicalizer get(UTF8StreamJsonParser jsonParser) {
              try {
                return jsonParser._symbols;
              } catch (NoSuchFieldError e) {
                latch();
                throw e;
              }
            }
          };

  public static boolean fetchInterner(UTF8StreamJsonParser jsonParser) {
    ByteQuadsCanonicalizer symbols = SYMBOLS.getOrDefault(jsonParser);
    // no symbol table to ask: assume interned (see the class comment)
    return symbols == null || ByteQuadsCanonicalizer216Helper.fetchInterner(symbols);
  }
}
