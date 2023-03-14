package datadog.trace.agent.tooling.bytebuddy.iast

import datadog.trace.api.iast.Taintable
import groovy.transform.CompileDynamic
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Visibility
import spock.lang.Specification

@CompileDynamic
class TaintableVisitorTest extends Specification {

  void 'test taintable visitor'() {
    given:
    final className = 'datadog.trace.agent.tooling.bytebuddy.iast.TaintableTest'
    final source = Mock(Taintable.Source)
    final builder = new ByteBuddy()
      .subclass(Object)
      .name(className)
      .merge(Visibility.PUBLIC)
      .visit(new TaintableVisitor(className))

    when:
    final clazz = builder
      .make()
      .load(Thread.currentThread().contextClassLoader)
      .loaded

    then:
    clazz != null
    clazz.interfaces.contains(Taintable)

    when:
    final taintable = clazz.newInstance() as Taintable

    then:
    taintable != null
    taintable.$$DD$getSource() == null

    when:
    taintable.$$DD$setSource(source)
    taintable.$$DD$getSource().getOrigin()

    then:
    1 * source.getOrigin()
  }

  void 'test taintable visitor with existing interface'() {
    given:
    final className = 'datadog.trace.agent.tooling.bytebuddy.iast.TaintableTest'
    final source = Mock(Taintable.Source)
    final builder = new ByteBuddy()
      .subclass(Cloneable)
      .name(className)
      .merge(Visibility.PUBLIC)
      .visit(new TaintableVisitor(className))

    when:
    final clazz = builder
      .make()
      .load(Thread.currentThread().contextClassLoader)
      .loaded

    then:
    clazz != null
    final interfaces = clazz.interfaces
    interfaces.contains(Taintable)
    interfaces.contains(Cloneable)


    when:
    final taintable = clazz.newInstance() as Taintable

    then:
    taintable != null
    taintable.$$DD$getSource() == null

    when:
    taintable.$$DD$setSource(source)
    taintable.$$DD$getSource().getOrigin()

    then:
    1 * source.getOrigin()
  }
}
