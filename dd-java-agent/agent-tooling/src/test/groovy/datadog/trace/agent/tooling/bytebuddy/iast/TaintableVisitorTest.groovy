package datadog.trace.agent.tooling.bytebuddy.iast

import datadog.trace.agent.tooling.bytebuddy.LoadedTaintableClass
import datadog.trace.api.iast.Taintable
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule
import net.bytebuddy.utility.nullability.MaybeNull

import java.security.ProtectionDomain

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class TaintableVisitorTest extends DDSpecification {

  private boolean wasEnabled

  void setup() {
    wasEnabled = TaintableVisitor.ENABLED
  }

  void cleanup() {
    TaintableVisitor.ENABLED = wasEnabled
  }

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

  void 'test taintable visitor with already loaded class'() {
    given:
    final instance = new LoadedTaintableClass()
    final listener = Mock(AgentBuilder.RedefinitionStrategy.Listener)

    when:
    final result = instance.sayHello()

    then:
    result == 'Hello!'

    when:
    new AgentBuilder.Default()
      .disableClassFormatChanges()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .with(TaintableRedefinitionStrategyListener.INSTANCE)
      .with(listener)
      .type(named(LoadedTaintableClass.name))
      .transform(new AgentBuilder.Transformer.ForAdvice().advice(named('sayHello'), 'datadog.trace.agent.tooling.bytebuddy.iast.MrReturnAdvice'))
      .transform(new AgentBuilder.Transformer() {
        @Override
        DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          @MaybeNull ClassLoader classLoader,
          @MaybeNull JavaModule module,
          ProtectionDomain protectionDomain) {
          return builder.visit(new TaintableVisitor(LoadedTaintableClass.name))
        }
      })
      .installOn(ByteBuddyAgent.instrumentation)

    then:
    final modifiedResult = instance.sayHello()

    then:
    modifiedResult == 'Hello Mr!'
    // failing initial batch
    1 * listener.onBatch(0, { List<Class<?>> list -> list.contains(LoadedTaintableClass) }, _)
    1 * listener.onError(0, { List<Class<?>> list -> list.contains(LoadedTaintableClass) }, _, _) >> []

    // successful batch after disabling the visitor
    1 * listener.onBatch(1, { List<Class<?>> list -> list.contains(LoadedTaintableClass) }, _)

    // finally two batches where executed
    1 * listener.onComplete(2, { List<Class<?>> list -> list.contains(LoadedTaintableClass) }, _)
  }
}
