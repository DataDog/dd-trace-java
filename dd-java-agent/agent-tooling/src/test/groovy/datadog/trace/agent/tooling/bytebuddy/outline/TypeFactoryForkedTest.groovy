package datadog.trace.agent.tooling.bytebuddy.outline

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators
import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers
import net.bytebuddy.description.type.TypeDescription
import spock.lang.Shared
import spock.lang.Specification

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresContextField
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperMethod
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class TypeFactoryForkedTest extends Specification {
  static {
    DDElementMatchers.registerAsSupplier()
  }

  @Shared
  def typeFactory = TypeFactory.typeFactory.get()

  @Shared
  def hasSuperType = hasSuperType(named('java.util.concurrent.Future'))

  @Shared
  def hasSuperMethod = hasSuperMethod(named('tryFire'))

  @Shared
  def hasContextField = declaresContextField('java.lang.Runnable', 'java.lang.String')

  def "can mix full types with outlines"() {
    expect:
    TypeDescription.AbstractBase.RAW_TYPES // this test relies on raw-types

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
