package trace.instrumentation.crac;

import org.crac.Context;
import org.crac.Resource;

public final class TracerResource implements Resource {
  @Override
  public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
    System.err.println("BEFORE CHECKPOINT");
  }

  @Override
  public void afterRestore(Context<? extends Resource> context) throws Exception {
    System.err.println("AFTER RESTORE");
  }
}
