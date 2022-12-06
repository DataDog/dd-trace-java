package datadog.trace.util.stacktrace;

import static datadog.trace.util.stacktrace.StackWalkerTestUtil.isRunningJDK8WithHotSpot;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class HotSpotStackTraceIteratorTest {

  @BeforeAll
  public static void setUp() {
    assumeTrue(isRunningJDK8WithHotSpot());
  }

  @Test
  public void hasNext() {
    assertTrue(getTestIterator().hasNext());
  }

  @Test
  public void next() {
    assertNotNull(getTestIterator().next());
  }

  @Test
  public void next_throws_NoSuchElementException_when_there_are_no_more_elements() {
    HotSpotStackTraceIterator iterator = getTestIterator();
    while (iterator.hasNext()) {
      iterator.next();
    }
    Assertions.assertThrows(NoSuchElementException.class, iterator::next);
  }

  @Test
  public void remove_throws_UnsupportedOperationException() {
    Assertions.assertThrows(UnsupportedOperationException.class, getTestIterator()::remove);
  }

  private HotSpotStackTraceIterator getTestIterator() {
    return new HotSpotStackTraceIterator(
        new Throwable(), sun.misc.SharedSecrets.getJavaLangAccess());
  }
}
