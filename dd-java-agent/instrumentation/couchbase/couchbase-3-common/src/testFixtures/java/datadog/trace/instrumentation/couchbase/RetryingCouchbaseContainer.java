package datadog.trace.instrumentation.couchbase;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.time.Duration;
import org.testcontainers.couchbase.CouchbaseContainer;
import org.testcontainers.utility.DockerImageName;

/** Retries the transient Couchbase node-renaming race during container initialization. */
public final class RetryingCouchbaseContainer extends CouchbaseContainer {
  private static final int MAX_RENAME_ATTEMPTS = 3;
  private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

  public RetryingCouchbaseContainer(DockerImageName dockerImageName) {
    super(dockerImageName);
  }

  @Override
  protected void containerIsStarting(InspectContainerResponse containerInfo) {
    for (int attempt = 1; attempt <= MAX_RENAME_ATTEMPTS; attempt++) {
      try {
        super.containerIsStarting(containerInfo);
        return;
      } catch (RuntimeException failure) {
        if (attempt == MAX_RENAME_ATTEMPTS || !isTransientRenameFailure(failure)) {
          throw failure;
        }
        logger().warn("Couchbase node rename failed during startup; retrying", failure);
        try {
          Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted while retrying Couchbase node rename", interrupted);
        }
      }
    }
  }

  static boolean isTransientRenameFailure(Throwable failure) {
    boolean renameFailed = false;
    boolean transportFailed = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      transportFailed |= cause instanceof IOException;
      for (StackTraceElement frame : cause.getStackTrace()) {
        renameFailed |=
            frame.getClassName().equals(CouchbaseContainer.class.getName())
                && frame.getMethodName().equals("renameNode");
      }
    }
    return renameFailed && transportFailed;
  }
}
