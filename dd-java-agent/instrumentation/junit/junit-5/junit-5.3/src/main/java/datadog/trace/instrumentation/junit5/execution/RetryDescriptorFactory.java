package datadog.trace.instrumentation.junit5.execution;

import java.util.function.UnaryOperator;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;

/**
 * Builds a re-executable copy of a leaf test descriptor carrying a transformed unique id,
 * <b>without mutating final fields</b> (JEP 500).
 */
public interface RetryDescriptorFactory {

  /**
   * @return a reconstructed, re-executable copy with the transformed id, or {@code null} to fall
   *     back to the generic (Unsafe/reflection) clone.
   */
  TestDescriptor copy(TestDescriptor original, UnaryOperator<UniqueId> idTransform);
}
