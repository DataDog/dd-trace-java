package datadog.trace.agent.tooling.bytebuddy.outline

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators
import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers
import spock.lang.Shared
import spock.lang.Specification

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class TypeFactoryForkedTest extends Specification {

  @Shared
  def typeFactory = TypeFactory.typeFactory.get()

  @Shared
  def matchers = new DDElementMatchers()

  @Shared
  def hasSuperType = matchers.hasSuperType(named('java.util.concurrent.Future'))

  @Shared
  def hasSuperMethod = matchers.hasSuperMethod(named('tryFire'))

  @Shared
  def hasContextField = matchers.declaresContextField('java.lang.Runnable', 'java.lang.String')

  def "can mix full types with outlines"() {
    when:
    def systemLoader = ClassLoader.systemClassLoader
    def systemLocator = ClassFileLocators.classFileLocator(systemLoader)
    def t0 = 'java.util.concurrent.Future'
    def t1 = 'java.util.concurrent.ForkJoinTask'
    def t2 = 'java.util.concurrent.CompletableFuture$Completion'
    def t3 = 'java.util.concurrent.CompletableFuture$Signaller'

    typeFactory.beginInstall()
    typeFactory.switchContext(systemLoader)
    typeFactory.beginTransform(t2, systemLocator.locate(t2).resolve())
    // resolve t0 as outline type
    TypeFactory.findType(t0).getDeclaredMethods()
    typeFactory.enableFullDescriptions()
    // resolve t1 and t2 as full types
    TypeFactory.findType(t1).getDeclaredMethods()
    TypeFactory.findType(t2).getDeclaredMethods()
    typeFactory.endTransform()
    typeFactory.beginTransform(t3, systemLocator.locate(t3).resolve())
    def target = TypeFactory.findType(t3)

    then:
    // exercise going from outline (t3) to full types (t1, t2) and back again to outline (t0)
    !hasContextField.matches(target)
    target.getDeclaredMethods().find {hasSuperMethod.matches(it) }.name == 'tryFire'
    hasSuperType.matches(target)

    cleanup:
    typeFactory.endTransform()
    typeFactory.endInstall()
  }
}
