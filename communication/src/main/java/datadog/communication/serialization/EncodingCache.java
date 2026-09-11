package datadog.communication.serialization;

import datadog.trace.api.function.BackgroundOnly;

// TODO @FunctionalInterface
/**
 * {@link BackgroundOnly}: every implementation ({@link SimpleUtf8Cache}, {@link
 * GenerationalUtf8Cache}) is only safe to call from the writer/serializer thread that owns it.
 */
@BackgroundOnly
public interface EncodingCache {

  byte[] encode(CharSequence s);
}
