package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.DDCachingPoolStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDClassFileTransformer;
import datadog.trace.agent.tooling.bytebuddy.DDLocationStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDOutlinePoolStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDOutlineTypeStrategy;
import datadog.trace.agent.tooling.bytebuddy.DDRediscoveryStrategy;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import net.bytebuddy.agent.builder.AgentBuilder.ClassFileBufferStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.LocationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.PoolStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.DiscoveryStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.TransformerDecorator;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class contains class references for objects shared by the agent installer as well as muzzle
 * (both compile and runtime). Extracted out from AgentInstaller to begin separating some of the
 * logic out.
 */
public class AgentStrategies {
  private static final Logger log = LoggerFactory.getLogger(AgentStrategies.class);

  private static TransformerDecorator loadTransformerDecorator() {
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

  private static final TransformerDecorator TRANSFORMER_DECORATOR = loadTransformerDecorator();
  private static final DiscoveryStrategy REDISCOVERY_STRATEGY = new DDRediscoveryStrategy();
  private static final LocationStrategy LOCATION_STRATEGY = new DDLocationStrategy();

  private static final PoolStrategy POOL_STRATEGY;
  private static final ClassFileBufferStrategy BUFFER_STRATEGY;
  private static final TypeStrategy TYPE_STRATEGY;

  static {
    if (Config.get().isResolverOutlinePoolEnabled()) {
      DDOutlinePoolStrategy.registerTypePoolFacade();
      POOL_STRATEGY = DDOutlinePoolStrategy.INSTANCE;
      BUFFER_STRATEGY = DDOutlineTypeStrategy.INSTANCE;
      TYPE_STRATEGY = DDOutlineTypeStrategy.INSTANCE;
    } else {
      DDCachingPoolStrategy.registerAsSupplier();
      POOL_STRATEGY = DDCachingPoolStrategy.INSTANCE;
      BUFFER_STRATEGY = ClassFileBufferStrategy.Default.RETAINING;
      TYPE_STRATEGY = TypeStrategy.Default.REDEFINE_FROZEN;
    }
  }

  public static TransformerDecorator transformerDecorator() {
    return TRANSFORMER_DECORATOR;
  }

  public static DiscoveryStrategy rediscoveryStrategy() {
    return REDISCOVERY_STRATEGY;
  }

  public static LocationStrategy locationStrategy() {
    return LOCATION_STRATEGY;
  }

  public static PoolStrategy poolStrategy() {
    return POOL_STRATEGY;
  }

  public static ClassFileBufferStrategy bufferStrategy() {
    return BUFFER_STRATEGY;
  }

  public static TypeStrategy typeStrategy() {
    return TYPE_STRATEGY;
  }
}
