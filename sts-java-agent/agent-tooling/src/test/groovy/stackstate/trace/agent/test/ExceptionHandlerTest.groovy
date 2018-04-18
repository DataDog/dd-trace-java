package stackstate.trace.agent.test

import stackstate.trace.bootstrap.ExceptionLogger
import stackstate.trace.agent.tooling.ExceptionHandlers
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.dynamic.ClassFileLocator

import static net.bytebuddy.matcher.ElementMatchers.isMethod
import static net.bytebuddy.matcher.ElementMatchers.named

import net.bytebuddy.agent.builder.AgentBuilder
import spock.lang.Specification
import spock.lang.Shared

import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.classic.Level

class ExceptionHandlerTest extends Specification {
  @Shared
  ListAppender testAppender = new ListAppender()

  def setupSpec() {
    AgentBuilder builder = new AgentBuilder.Default()
      .disableClassFormatChanges()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .type(named(getClass().getName()+'$SomeClass'))
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
        isMethod().and(named("smallStack").or(named("largeStack"))),
        BadAdvice.NoOpAdvice.getName()))
      .asDecorator()

    ByteBuddyAgent.install()
    builder.installOn(ByteBuddyAgent.getInstrumentation())

    final Logger logger = (Logger) LoggerFactory.getLogger(ExceptionLogger)
    testAppender.setContext(logger.getLoggerContext())
    logger.addAppender(testAppender)
    testAppender.start()
  }

  def cleanupSpec() {
    testAppender.stop()
  }

  def "exception handler invoked"() {
    setup:
    int initLogEvents = testAppender.list.size()
    expect:
    SomeClass.isInstrumented()
    testAppender.list.size() == initLogEvents + 1
    testAppender.list.get(testAppender.list.size() - 1).getLevel() == Level.DEBUG
    // Make sure the log event came from our error handler.
    // If the log message changes in the future, it's fine to just
    // update the test's hardcoded message
    testAppender.list.get(testAppender.list.size() - 1).getMessage() == "Failed to handle exception in instrumentation"
  }

  def "exception on non-delegating classloader" () {
    setup:
    int initLogEvents = testAppender.list.size()
    URL[] classpath = [ SomeClass.getProtectionDomain().getCodeSource().getLocation(),
                         GroovyObject.getProtectionDomain().getCodeSource().getLocation() ]
    URLClassLoader loader = new URLClassLoader(classpath, (ClassLoader) null)
    when:
    loader.loadClass(LoggerFactory.getName())
    then:
    thrown ClassNotFoundException

    when:
    Class<?> someClazz = loader.loadClass(SomeClass.getName())
    then:
    someClazz.getClassLoader() == loader
    someClazz.getMethod("isInstrumented").invoke(null)
    testAppender.list.size() == initLogEvents
  }

  def "exception handler sets the correct stack size"() {
    when:
    SomeClass.smallStack()
    SomeClass.largeStack()

    then:
    noExceptionThrown()
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
  }
}
