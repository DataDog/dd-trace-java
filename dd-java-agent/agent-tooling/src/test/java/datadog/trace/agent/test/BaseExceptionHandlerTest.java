package datadog.trace.agent.test;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy.RETRANSFORMATION;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import datadog.trace.bootstrap.ExceptionLogger;
import datadog.trace.bootstrap.InstrumentationErrors;
import datadog.trace.test.util.DDJavaSpecification;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.Permission;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Base class for the exception handler tests. The SecurityManager used in the test is marked for
 * removal and throws exceptions on Java 21+, so concrete subclasses must declare
 * {@code @EnabledForJreRange(max = JAVA_20)}: JUnit condition annotations are not inherited.
 */
abstract class BaseExceptionHandlerTest extends DDJavaSpecification {
  private static final ListAppender<ILoggingEvent> testAppender = new ListAppender<>();

  private static ResettableClassFileTransformer transformer;

  private SecurityManager defaultSecurityManager;

  private AtomicInteger exitStatus;

  @BeforeAll
  static void installTransformer() {
    ClassFileLocator adviceLocator =
        ClassFileLocator.ForClassLoader.of(BadAdvice.class.getClassLoader());
    AgentBuilder builder =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(RETRANSFORMATION)
            .type(named(BaseExceptionHandlerTest.class.getName() + "$SomeClass"))
            .transform(
                new AgentBuilder.Transformer.ForAdvice()
                    .with(new AgentBuilder.LocationStrategy.Simple(adviceLocator))
                    .withExceptionHandler(
                        ExceptionHandlers.exceptionHandlerFor(BadAdvice.class.getName()))
                    .advice(isMethod().and(named("isInstrumented")), BadAdvice.class.getName()))
            .transform(
                new AgentBuilder.Transformer.ForAdvice()
                    .with(new AgentBuilder.LocationStrategy.Simple(adviceLocator))
                    .withExceptionHandler(
                        ExceptionHandlers.exceptionHandlerFor(BadAdvice.NoOpAdvice.class.getName()))
                    .advice(
                        isMethod().and(namedOneOf("smallStack", "largeStack")),
                        BadAdvice.NoOpAdvice.class.getName()))
            .transform(
                new AgentBuilder.Transformer.ForAdvice()
                    .with(new AgentBuilder.LocationStrategy.Simple(adviceLocator))
                    .withExceptionHandler(
                        ExceptionHandlers.exceptionHandlerFor(
                            BlockingExceptionAdvice.class.getName()))
                    .advice(
                        isMethod().and(named("blockingException")),
                        BlockingExceptionAdvice.class.getName()));

    ByteBuddyAgent.install();
    transformer = builder.installOn(ByteBuddyAgent.getInstrumentation());

    Logger logger = (Logger) LoggerFactory.getLogger(ExceptionLogger.class);
    testAppender.setContext(logger.getLoggerContext());
    logger.addAppender(testAppender);
    testAppender.start();
  }

  @AfterAll
  static void resetTransformer() {
    testAppender.stop();
    transformer.reset(ByteBuddyAgent.getInstrumentation(), RETRANSFORMATION);
  }

  @BeforeEach
  void installNoExitSecurityManager() {
    exitStatus = new AtomicInteger(0);

    defaultSecurityManager = System.getSecurityManager();
    System.setSecurityManager(new NoExitSecurityManager(exitStatus));
  }

  @AfterEach
  void restoreSecurityManager() {
    System.setSecurityManager(defaultSecurityManager);
  }

  protected abstract int expectedFailureExitStatus();

  protected abstract Level expectedFailureLogLevel();

  protected boolean expectedBlockingException() {
    return true;
  }

  @Test
  void exceptionHandlerInvoked() {
    int initLogEvents = testAppender.list.size();

    assertTrue(SomeClass.isInstrumented());
    assertEquals(initLogEvents + 1, testAppender.list.size());
    ILoggingEvent lastEvent = testAppender.list.get(testAppender.list.size() - 1);
    assertEquals(expectedFailureLogLevel(), lastEvent.getLevel());
    // Make sure the log event came from our error handler.
    // If the log message changes in the future, it's fine to just
    // update the test's hardcoded message
    assertEquals(
        "Failed to handle exception in instrumentation for "
            + SomeClass.class.getName()
            + " - "
            + BadAdvice.class.getName(),
        lastEvent.getMessage());
    assertEquals(expectedFailureExitStatus(), exitStatus.get());
  }

  @Test
  void exceptionOnNonDelegatingClassloader() throws Exception {
    int initLogEvents = testAppender.list.size();
    URL[] classpath = {SomeClass.class.getProtectionDomain().getCodeSource().getLocation()};
    URLClassLoader loader = new BlockingTestClassLoader(classpath);

    assertThrows(
        ClassNotFoundException.class,
        () -> loader.loadClass(InstrumentationErrors.class.getName()));

    Class<?> someClazz = loader.loadClass(SomeClass.class.getName());
    assertEquals(loader, someClazz.getClassLoader());
    assertTrue((boolean) someClazz.getMethod("isInstrumented").invoke(null));
    assertEquals(initLogEvents, testAppender.list.size());
    assertEquals(0, exitStatus.get());
  }

  @Test
  void exceptionHandlerSetsTheCorrectStackSize() {
    assertDoesNotThrow(() -> {
      SomeClass.smallStack();
      SomeClass.largeStack();
    });
    assertEquals(0, exitStatus.get());
  }

  @Test
  void blockingExceptionIsRethrown() {
    Throwable exception = null;
    try {
      SomeClass.blockingException();
    } catch (Throwable t) {
      exception = t;
    }

    if (expectedBlockingException()) {
      assertInstanceOf(BlockingException.class, exception);
    } else {
      assertNull(exception);
    }
  }

  public static class SomeClass {
    public static boolean isInstrumented() {
      return false;
    }

    public static void smallStack() {
      // a method with a max stack of 0
    }

    public static void largeStack() {
      // a method with a large max stack
      long longValue = 22L;
      int intValue = 3;
      double doubleValue = 32.2d;
      Object object = new Object();
      System.out.printf("large stack: %d %d %f %s", longValue, intValue, doubleValue, object);
    }

    public static void blockingException() {
      // do nothing and throw from the advice
    }
  }

  private static class NoExitSecurityManager extends SecurityManager {
    private final AtomicInteger status;

    NoExitSecurityManager(AtomicInteger status) {
      this.status = status;
    }

    @Override
    public void checkPermission(Permission perm) {
      // allow anything
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
      // allow anything
    }

    @Override
    public void checkExit(int status) {
      super.checkExit(status);
      this.status.set(status);
      throw new IllegalAccessError();
    }
  }
}
