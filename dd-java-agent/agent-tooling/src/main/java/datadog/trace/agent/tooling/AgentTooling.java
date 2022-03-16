package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDClassFileTransformer;
import datadog.trace.agent.tooling.bytebuddy.DDLocationStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDRediscoveryStrategy;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import net.bytebuddy.agent.builder.AgentBuilder.TransformerDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class contains class references for objects shared by the agent installer as well as muzzle
 * (both compile and runtime). Extracted out from AgentInstaller to begin separating some of the
 * logic out.
 */
public class AgentTooling {
  private static final Logger log = LoggerFactory.getLogger(AgentTooling.class);

  private static TransformerDecorator loadTranformerDecorator() {
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        return (TransformerDecorator)
            Instrumenter.class
                .getClassLoader()
                .loadClass("datadog.trace.agent.tooling.bytebuddy.DDJava9ClassFileTransformer")
                .getField("DECORATOR")
                .get(null);
      } catch (Throwable e) {
        log.warn("Problem loading Java9 Module support, falling back to legacy transformer", e);
      }
    }
    return DDClassFileTransformer.DECORATOR;
  }

  private static final DDRediscoveryStrategy REDISCOVERY_STRATEGY = new DDRediscoveryStrategy();
  private static final DDLocationStrategy LOCATION_STRATEGY = new DDLocationStrategy();
  private static final DDCachingPoolStrategy POOL_STRATEGY =
      new DDCachingPoolStrategy(Config.get().isResolverUseLoadClassEnabled());
  private static final TransformerDecorator TRANSFORMER_DECORATOR = loadTranformerDecorator();

  public static DDRediscoveryStrategy rediscoveryStrategy() {
    return REDISCOVERY_STRATEGY;
  }

  public static DDLocationStrategy locationStrategy() {
    return LOCATION_STRATEGY;
  }

  public static DDCachingPoolStrategy poolStrategy() {
    return POOL_STRATEGY;
  }

  public static TransformerDecorator transformerDecorator() {
    return TRANSFORMER_DECORATOR;
  }
}
