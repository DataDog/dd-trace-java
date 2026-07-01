package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.test.DirectRunnable
import datadog.trace.agent.test.ExtendedRunnable
import datadog.trace.agent.test.LeafFutureTask
import datadog.trace.agent.test.NoInterfacesInTheMiddle
import net.bytebuddy.description.type.TypeDescription

import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class RealTypeHierarchyMatcherTests extends AbstractHierarchyMatcherTest {

  def "test implementsInterface: #name"() {
    setup:
    def matcher = implementsInterface(named(Runnable.getName()))

    expect:
    matcher.matches(new TypeDescription.ForLoadedType(clazz)) == implementsRunnable

    where:
    clazz                   | implementsRunnable | name
    DirectRunnable          | true               | "direct match"
    FutureTask              | true               | "transitive match via interface"
    NoInterfacesInTheMiddle | true               | "transitive match via class then interface"
    ForkJoinTask            | false              | "class extends Object has no match"
    StringBuffer            | false              | "no match with class inheritance"
    Callable                | false              | "interface doesn't match"
    ExtendedRunnable        | false              | "reject interface targets, even if they have the interface"
    Runnable                | false              | "an interface can't implement itself"
  }

  def "test hasInterface: #name"() {
    setup:
    def matcher = hasInterface(named(Runnable.getName()))

    expect:
    matcher.matches(new TypeDescription.ForLoadedType(clazz)) == implementsRunnable

    where:
    clazz                   | implementsRunnable | name
    DirectRunnable          | true               | "direct match"
    FutureTask              | true               | "transitive match via interface"
    NoInterfacesInTheMiddle | true               | "transitive match via class then interface"
    ForkJoinTask            | false              | "class extends Object has no match"
    StringBuffer            | false              | "no match with class inheritance"
    Callable                | false              | "interface doesn't match"
    ExtendedRunnable        | true               | "accept interface targets"
    Runnable                | true               | "accept the interface itself"
  }

  def "test hasSuperType: #name"() {
    setup:
    def matcher = hasSuperType(named(Runnable.getName()))

    expect:
    matcher.matches(new TypeDescription.ForLoadedType(clazz)) == implementsRunnable

    where:
    clazz                   | implementsRunnable | name
    DirectRunnable          | true               | "direct match"
    FutureTask              | true               | "transitive match via interface"
    NoInterfacesInTheMiddle | true               | "transitive match via class then interface"
    ForkJoinTask            | false              | "class extends Object has no match"
    StringBuffer            | false              | "no match with class inheritance"
    Callable                | false              | "interface doesn't match"
    ExtendedRunnable        | false              | "reject interface targets"
    Runnable                | false              | "reject the interface itself"
  }

  def "test extendsClass: #name"() {
    setup:
    def matcher = extendsClass(named(FutureTask.getName()))

    expect:
    matcher.matches(new TypeDescription.ForLoadedType(clazz)) == extendsFutureTask

    where:
    clazz                   | extendsFutureTask | name
    FutureTask              | true              | "match the class itself"
    NoInterfacesInTheMiddle | true              | "direct supertype relationship"
    LeafFutureTask          | true              | "transitive supertype relationship"
    ForkJoinTask            | false             | "class extends Object has no match"
    StringBuffer            | false             | "no match with class inheritance"
    ExtendedRunnable        | false             | "reject interface targets"
  }
}
