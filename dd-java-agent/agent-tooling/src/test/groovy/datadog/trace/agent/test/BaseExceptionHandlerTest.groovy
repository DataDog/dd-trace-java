package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import datadog.appsec.api.blocking.BlockingException
import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers
import datadog.trace.bootstrap.ExceptionLogger
import datadog.trace.bootstrap.InstrumentationErrors
import datadog.trace.bootstrap.blocking.BlockingExceptionHandler
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.dynamic.ClassFileLocator
import org.slf4j.LoggerFactory
import spock.lang.IgnoreIf
import spock.lang.Shared

import java.security.Permission
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf
import static net.bytebuddy.matcher.ElementMatchers.isMethod
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

@IgnoreIf(reason = "SecurityManager used in the test is marked for removal and throws exceptions", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(21)
})
abstract class BaseExceptionHandlerTest extends DDSpecification {
  @Shared
  ListAppender testAppender = new ListAppender()

  @Shared
  ResettableClassFileTransformer transformer

  @Shared
  SecurityManager defaultSecurityManager = null

  @Shared
  AtomicInteger exitStatus = null

  def setupSpec() {
    changeConfig()

    AgentBuilder builder = new AgentBuilder.Default()
      .disableClassFormatChanges()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .type(named(BaseExceptionHandlerTest.getName() + '$SomeClass'))
      .transform(
      new AgentBuilder.Transformer.ForAdvice()
      .with(new AgentBuilder.LocationStrategy.Simple(ClassFileLocator.ForClassLoader.of(BadAdvice.getClassLoader())))
      .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
      .advice(
      isMethod().and(named("isInstrumented")),
      BadAdvice.getName()))
      .transform(
      new AgentBuilder.Transformer.ForAdvice()
      .with(new AgentBuilder.LocationStrategy.Simple(ClassFileLocator.ForClassLoader.of(BadAdvice.getClassLoader())))
      .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
      .advice(
      isMethod().and(namedOneOf("smallStack", "largeStack")),
      BadAdvice.NoOpAdvice.getName()))
      .transform(
      new AgentBuilder.Transformer.ForAdvice()
      .with(new AgentBuilder.LocationStrategy.Simple(ClassFileLocator.ForClassLoader.of(BadAdvice.getClassLoader())))
      .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
      .advice(
      isMethod().and(named("blockingException")),
      BlockingExceptionAdvice.getName()))

    ByteBuddyAgent.install()
    transformer = builder.installOn(ByteBuddyAgent.getInstrumentation())

    final Logger logger = (Logger) LoggerFactory.getLogger(ExceptionLogger)
    testAppender.setContext(logger.getLoggerContext())
    logger.addAppender(testAppender)
    testAppender.start()
  }

  def cleanupSpec() {
    testAppender.stop()
    transformer.reset(ByteBuddyAgent.getInstrumentation(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
  }

  def setup() {
    changeConfig()

    exitStatus = new AtomicInteger(0)

    defaultSecurityManager = System.securityManager
    System.setSecurityManager(new NoExitSecurityManager(exitStatus))
  }

  def cleanup() {
    System.setSecurityManager(defaultSecurityManager)
  }

  abstract protected void changeConfig()

  abstract protected int expectedFailureExitStatus()

  abstract protected Level expectedFailureLogLevel()

  protected boolean expectedBlockingException() {
    true
  }

  def "exception handler invoked"() {
    setup:
    int initLogEvents = testAppender.list.size()
    expect:
    SomeClass.isInstrumented()
    testAppender.list.size() == initLogEvents + 1
    testAppender.list.get(testAppender.list.size() - 1).getLevel() == expectedFailureLogLevel()
    // Make sure the log event came from our error handler.
    // If the log message changes in the future, it's fine to just
    // update the test's hardcoded message
    testAppender.list.get(testAppender.list.size() - 1).getMessage().startsWith("Failed to handle exception in instrumentation for")
    exitStatus.get() == expectedFailureExitStatus()
  }

  def "exception on non-delegating classloader"() {
    setup:
    int initLogEvents = testAppender.list.size()
    URL[] classpath = [
      SomeClass.getProtectionDomain().getCodeSource().getLocation(),
      GroovyObject.getProtectionDomain().getCodeSource().getLocation(),
    ]
    URLClassLoader loader = new URLClassLoader(classpath, null, null) {
        @Override
        Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
          if (name == BlockingExceptionHandler.name) {
            return BlockingExceptionHandler
          }
          if (name == BlockingException.name) {
            return BlockingException
          }
          return super.loadClass(name, resolve)
        }
      }

    when:
    loader.loadClass(InstrumentationErrors.getName())
    then:
    thrown ClassNotFoundException

    when:
    Class<?> someClazz = loader.loadClass(SomeClass.getName())
    then:
    someClazz.getClassLoader() == loader
    someClazz.getMethod("isInstrumented").invoke(null)
    testAppender.list.size() == initLogEvents
    exitStatus.get() == 0
  }

  def "exception handler sets the correct stack size"() {
    when:
    SomeClass.smallStack()
    SomeClass.largeStack()

    then:
    noExceptionThrown()
    exitStatus.get() == 0
  }

  void 'blocking exception is rethrown'() {
    when:
    Throwable exception = null
    try {
      SomeClass.blockingException()
    } catch (Throwable t) {
      exception = t
    }

    then:
    if (expectedBlockingException()) {
      exception instanceof BlockingException
    } else {
      exception == null
    }
  }

  static class SomeClass {
    static boolean isInstrumented() {
      return false
    }

    static void smallStack() {
      // a method with a max stack of 0
    }

    static void largeStack() {
      // a method with a max stack of 6
      long l = 22l
      int i = 3
      double d = 32.2d
      Object o = new Object()
      println "large stack: $l $i $d $o"
    }

    static void blockingException() {
      // do nothing and throw from the advice
    }
  }

  private static class NoExitSecurityManager extends SecurityManager {
    private final AtomicInteger status

    NoExitSecurityManager(AtomicInteger status) {
      this.status = status
    }

    @Override
    void checkPermission(Permission perm) {
      // allow anything
    }

    @Override
    void checkPermission(Permission perm, Object context) {
      // allow anything
    }

    @Override
    void checkExit(int status) {
      super.checkExit(status)
      this.status.set(status)
      throw new IllegalAccessError()
    }
  }
}
