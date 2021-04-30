package datadog.trace.api;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Checkpointers {

  private static final Logger log = LoggerFactory.getLogger(Checkpointers.class);

  private static final AtomicReferenceFieldUpdater<Checkpointers, Checkpointer> CAS =
      AtomicReferenceFieldUpdater.newUpdater(
          Checkpointers.class, Checkpointer.class, "checkpointer");

  private volatile Checkpointer checkpointer;

  private static final Checkpointers CHECKPOINTERS = new Checkpointers(NoOpCheckpointer.NO_OP);

  private static volatile Checkpointer CHECKPOINTER = CHECKPOINTERS.checkpointer;

  public Checkpointers(Checkpointer checkpointer) {
    this.checkpointer = checkpointer;
  }

  public static void register(Checkpointer checkpointer) {
    if (CAS.compareAndSet(CHECKPOINTERS, NoOpCheckpointer.NO_OP, checkpointer)) {
      CHECKPOINTER = CHECKPOINTERS.checkpointer;
    } else {
      log.debug(
          "failed to register checkpointer {} - {} already registered",
          checkpointer.getClass(),
          get().getClass());
    }
  }

  public static Checkpointer get() {
    return CHECKPOINTER;
  }

  private static final class NoOpCheckpointer implements Checkpointer {

    static final NoOpCheckpointer NO_OP = new NoOpCheckpointer();

    @Override
    public void checkpoint(DDId traceId, DDId spanId, int flags) {}
  }
}
