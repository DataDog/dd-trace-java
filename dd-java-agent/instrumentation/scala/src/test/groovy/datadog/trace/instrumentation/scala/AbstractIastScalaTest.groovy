package datadog.trace.instrumentation.scala

import com.datadog.iast.test.IastAgentTestRunner
import spock.lang.Shared

abstract class AbstractIastScalaTest extends IastAgentTestRunner {

  @Shared
  boolean usesJavaConcat = Boolean.getBoolean('uses.java.concat')

  @Shared
  Object testSuite = newSuite()

  private Object newSuite() {
    final clazz = Thread.currentThread().contextClassLoader.loadClass(suiteName())
    return clazz.newInstance()
  }

  abstract String suiteName()
}
