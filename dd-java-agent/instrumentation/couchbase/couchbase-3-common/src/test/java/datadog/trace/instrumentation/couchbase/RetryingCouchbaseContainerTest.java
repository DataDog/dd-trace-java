package datadog.trace.instrumentation.couchbase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import org.junit.jupiter.api.Test;
import org.testcontainers.couchbase.CouchbaseContainer;

class RetryingCouchbaseContainerTest {
  @Test
  void retriesEofFromNodeRenaming() {
    assertTrue(isTransientRenameFailure(new EOFException(), "renameNode"));
  }

  @Test
  void retriesConnectionResetFromNodeRenaming() {
    assertTrue(isTransientRenameFailure(new SocketException(), "renameNode"));
  }

  @Test
  void doesNotRetryTransportFailureFromAnotherStartupPhase() {
    assertFalse(isTransientRenameFailure(new IOException(), "initializeServices"));
  }

  @Test
  void doesNotRetryNonTransportFailureFromNodeRenaming() {
    assertFalse(isTransientRenameFailure(new IllegalStateException(), "renameNode"));
  }

  private static boolean isTransientRenameFailure(Throwable cause, String method) {
    RuntimeException failure = new RuntimeException(cause);
    failure.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              CouchbaseContainer.class.getName(), method, "CouchbaseContainer.java", 1)
        });
    return RetryingCouchbaseContainer.isTransientRenameFailure(failure);
  }
}
