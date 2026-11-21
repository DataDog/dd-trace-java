package datadog.context.propagation;

/**
 * A carrier visitor's callback that can take a value as a transient view: a {@code CharSequence}
 * valid only during the call, for a carrier that holds values as bytes and can avoid decoding them.
 * A carrier may hand a value this way only for a key whose {@link HeaderSchema} constant {@link
 * HeaderSchema#isParseOnly() is parse-only}, and only if the visitor implements this interface;
 * otherwise it decodes the value and calls {@code accept(key, value)} as usual.
 *
 * <p>An implementation must not keep the view past the call, nor hand it to anything that might: it
 * may share the carrier's whole buffer. It copies the value ({@code toString()}) to keep it.
 */
public interface TransientValueConsumer {
  /**
   * Takes {@code key}'s value as a view valid only during this call.
   *
   * @param header {@code key}'s constant, which is parse-only
   * @param key the key
   * @param value the value, valid only during this call
   */
  void acceptTransient(HeaderSchema header, String key, CharSequence value);
}
