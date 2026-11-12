package context.benchmark.workload;

/**
 * Key type for the context-store workload benchmark.
 *
 * <p>Key types live outside {@code datadog.*} because the agent never instruments those packages.
 */
public interface WorkloadKey {
  /** Returns the context store with the given index; the method body is replaced by advice. */
  Object store(int storeIndex);

  /** Creates a new key of the same type. */
  WorkloadKey newKey();
}
