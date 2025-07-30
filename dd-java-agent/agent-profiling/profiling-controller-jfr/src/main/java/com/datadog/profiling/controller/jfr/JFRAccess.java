package com.datadog.profiling.controller.jfr;

import com.datadog.profiling.utils.Timestamper;
import java.lang.instrument.Instrumentation;
import java.util.ServiceLoader;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JFR access support.<br>
 * Provides access to the JFR internal API. For Java 9 and newer, the JFR access requires
 * instrumentation in order to patch the module access.
 */
public abstract class JFRAccess implements Timestamper {
  private static final Logger log = LoggerFactory.getLogger(JFRAccess.class);

  /** No-op JFR access implementation. */
  public static final JFRAccess NOOP =
      new JFRAccess() {
        @Override
        public boolean setStackDepth(int depth) {
          return false;
        }

        @Override
        public boolean setBaseLocation(String location) {
          return false;
        }

        @Override
        public long getThreadWriterPosition() {
          return -1;
        }
      };

  /**
   * Factory for JFR access.<br>
   * The factory is expected to return {@code null} if the JFR access is not available. The factory
   * is to be registered in {@code
   * META-INF/services/com.datadog.profiling.controller.jfr.JFRAccess$Factory} and will be
   * discovered using {@link ServiceLoader}.
   */
  public interface Factory {
    @Nullable
    JFRAccess create(@Nullable Instrumentation inst);
  }

  private static volatile JFRAccess INSTANCE = NOOP;

  /**
   * Returns the JFR access instance.
   *
   * @return the JFR access instance or {@link #NOOP} if the JFR access is not available
   */
  public static JFRAccess instance() {
    return INSTANCE;
  }

  /**
   * Sets up the JFR access.<br>
   * The method is expected to be called once, before any other method of this class is called.
   *
   * @param inst the instrumentation instance, may be {@code null}
   */
  public static void setup(@Nullable Instrumentation inst) {
    JFRAccess access = NOOP;
    if (inst != null) {
      for (Factory factory : ServiceLoader.load(Factory.class, JFRAccess.class.getClassLoader())) {
        JFRAccess candidate = factory.create(inst);
        if (candidate != null) {
          access = candidate;
          break;
        }
      }
    }
    log.debug("JFR access: {}", access.getClass().getName());
    INSTANCE = access;
  }

  /**
   * Sets the stack depth for the JFR recordings.<br>
   * It needs to be called before the recording is started.
   *
   * @param depth the stack depth
   * @return {@code true} if the stack depth was set successfully, {@code false} if not or it is not
   *     possible to tell
   */
  public abstract boolean setStackDepth(int depth);

  /**
   * Sets the base location for JFR repository for the current VM
   *
   * @param location the location path to set
   * @return {@code true} if the base location was set successfully, {@code false} if not
   */
  public abstract boolean setBaseLocation(String location);

  public abstract long getThreadWriterPosition();
}
