package context.benchmark;

import java.util.function.Function;

/**
 * Key type for the context-store benchmark; method bodies are replaced by advice.
 *
 * <p>Key types live outside {@code datadog.*} because the agent never instruments those packages.
 */
public interface ContextStoreBenchmarkKey {
  int read();

  void write(Object context);

  Object getContext(int storeIndex);

  void putContext(int storeIndex, Object context);

  Object removeContext(int storeIndex);

  Object getOrPutContext(int storeIndex, Object context);

  Object getOrComputeContext(int storeIndex, Function<Object, Object> contextFactory);

  ContextStoreBenchmarkKey newKey();
}
