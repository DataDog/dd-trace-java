package com.datadog.iast.util

import com.google.common.collect.Iterables
import foo.bar.VisitableClass
import spock.lang.Specification

import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE

class ObjectVisitorTest extends Specification {

  void 'test visiting simple type'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = '123'

    when:
    ObjectVisitor.visit(target, visitor) { false } // do not introspect objects

    then:
    1 * visitor.visit('root', target) >> CONTINUE
    0 * _
  }

  void 'test visiting collection'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = ['1', '2', '3']

    when:
    ObjectVisitor.visit(target, visitor) {Collection.isAssignableFrom(it) }

    then:
    target.eachWithIndex { value, index ->
      1 * visitor.visit("root[$index]", value) >> CONTINUE
    }
    0 * _
  }

  void 'test visiting iterable'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final wrapped = ['1', '2', '3']
    final target = Iterables.unmodifiableIterable(wrapped)

    when:
    ObjectVisitor.visit(target, visitor) { Iterable.isAssignableFrom(it) }

    then:
    wrapped.eachWithIndex { value, index ->
      1 * visitor.visit("root[$index]", value) >> CONTINUE
    }
    0 * _
  }

  void 'test visiting map'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = ['a': 'b']

    when:
    ObjectVisitor.visit(target, visitor) { Map.isAssignableFrom(it) }

    then:
    target.keySet().each { key ->
      1 * visitor.visit('root[]', key) >> CONTINUE
    }
    target.each { key, value ->
      1 * visitor.visit("root[$key]", value) >> CONTINUE
    }
    0 * _
  }

  void 'test visiting ignored object'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = new HashMap<>(['a': 'b'])

    when:
    ObjectVisitor.visit(target, visitor) { false }

    then:
    0 * _
  }

  void 'test max depth'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = [a : [b: ['c', 'd']]]
    final predicate = {Class<?> cls -> Map.isAssignableFrom(cls) || Collection.isAssignableFrom(cls)}

    when:
    ObjectVisitor.visit(target, visitor, predicate, 2, Integer.MAX_VALUE)

    then:
    1 * visitor.visit("root[]", 'a') >> CONTINUE
    1 * visitor.visit("root[a][]", 'b') >> CONTINUE
    0 * _
  }

  void 'test max objects'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = [1, 2, 3]
    final predicate = {Class<?> cls -> Collection.isAssignableFrom(cls)}

    when: 'we visit at most two objects'
    ObjectVisitor.visit(target, visitor, predicate, Integer.MAX_VALUE, 2)

    then: 'we visit the array and its first element'
    1 * visitor.visit("root[0]", 1) >> CONTINUE
    0 * _
  }

  void 'test cycles'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = new VisitableClass(name: 'cycle')
    target.cycle = target
    final predicate = {Class<?> cls -> VisitableClass.isAssignableFrom(cls)}

    when: 'we visit a class with a self reference'
    ObjectVisitor.visit(target, visitor, predicate, Integer.MAX_VALUE, Integer.MAX_VALUE)

    then: 'we only visit the class once'
    1 * visitor.visit("root", target) >> CONTINUE
    1 * visitor.visit("root.name", 'cycle') >> CONTINUE
    0 * _
  }
}
