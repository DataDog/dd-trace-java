package datadog.trace.agent.tooling.bytebuddy;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DDCircularityLock extends AgentBuilder.CircularityLock.Default {
  private static final Logger log = LoggerFactory.getLogger(DDCircularityLock.class);

  @Override
  public boolean acquire() {
    boolean result = super.acquire();
    log.info("ACQUIRE CIRCULARITY LOCK = {}", result);
    return result;
  }

  @Override
  public void release() {
    log.info("RELEASE CIRCULARITY LOCK");
    super.release();
  }
}
