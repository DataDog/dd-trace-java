package datadog.trace.instrumentation.junit5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.SpockNode;
import org.spockframework.runtime.model.FeatureInfo;
import spock.config.RunnerConfiguration;

class SpockLegacyTagsForkedTest {
  private static final String TAGGABLE = "org.spockframework.runtime.model.ITestTaggable";
  private static final String TAG = "org.spockframework.runtime.model.TestTag";

  @Test
  void missingNativeTagApisDoNotWarnForOrdinaryFeatures() throws Exception {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    URL[] helpers = {
      SpockUtils.class.getProtectionDomain().getCodeSource().getLocation(),
      TestDataFactory.class.getProtectionDomain().getCodeSource().getLocation()
    };
    try (URLClassLoader loader =
        new URLClassLoader(helpers, original) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(TAGGABLE) || name.equals(TAG)) {
              // These APIs are absent from supported Spock 2.0 and 2.1.
              throw new ClassNotFoundException(name);
            }
            if (name.equals(SpockUtils.class.getName())
                || name.equals(TestDataFactory.class.getName())
                || name.equals(
                    "datadog.trace.instrumentation.junit5.execution.RetryDescriptorFactories")) {
              Class<?> loaded = findLoadedClass(name);
              if (loaded == null) {
                loaded = findClass(name);
              }
              if (resolve) {
                resolveClass(loaded);
              }
              return loaded;
            }
            return super.loadClass(name, resolve);
          }
        }) {
      assertThrows(ClassNotFoundException.class, () -> loader.loadClass(TAGGABLE));
      assertThrows(ClassNotFoundException.class, () -> loader.loadClass(TAG));
      Thread.currentThread().setContextClassLoader(loader);
      Class<?> utils = Class.forName(SpockUtils.class.getName(), true, loader);
      for (String name : new String[] {"GET_TEST_TAGS", "GET_TEST_TAG_VALUE"}) {
        Field handle = utils.getDeclaredField(name);
        handle.setAccessible(true);
        assertNull(handle.get(null));
      }
      Method resolver = utils.getDeclaredMethod("getTestTags", TestDescriptor.class);
      resolver.setAccessible(true);
      Logger logger = (Logger) LoggerFactory.getLogger(SpockUtils.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      try {
        assertTrue(((Collection<?>) resolver.invoke(null, new LegacyFeatureNode())).isEmpty());
        assertFalse(
            appender.list.stream()
                .anyMatch(
                    log ->
                        log.getLevel() == Level.WARN
                            && log.getFormattedMessage()
                                .contains("Could not get tags from a spock node")));
      } finally {
        logger.detachAppender(appender);
        appender.stop();
      }
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  private static class LegacyFeatureNode extends SpockNode<FeatureInfo> {
    LegacyFeatureNode() {
      super(
          UniqueId.forEngine("spock"),
          "ordinary feature",
          null,
          new RunnerConfiguration(),
          new FeatureInfo());
    }

    @Override
    public Type getType() {
      return Type.TEST;
    }
  }
}
