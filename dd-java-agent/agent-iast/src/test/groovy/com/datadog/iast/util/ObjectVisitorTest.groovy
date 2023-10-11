package com.datadog.iast.util

import foo.bar.VisitableClass
import spock.lang.Specification

import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE

class ObjectVisitorTest extends Specification {

  void 'test visiting simple type'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = '123'

    when:
    ObjectVisitor.visit(target, visitor)

    then:
    1 * visitor.visit('root', target) >> CONTINUE
    0 * _
  }

  void 'test visiting collection'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = ['1', '2', '3']

    when:
    ObjectVisitor.visit(target, visitor)

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
    final target = new Iterable() {
        @Override
        Iterator iterator() {
          return wrapped.iterator()
        }
      }

    when:
    ObjectVisitor.visit(target, visitor)

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
    ObjectVisitor.visit(target, visitor)

    then:
    target.keySet().each { key ->
      1 * visitor.visit('root[]', key) >> CONTINUE
    }
    target.each { key, value ->
      1 * visitor.visit("root[$key]", value) >> CONTINUE
    }
    0 * _
  }

  void 'test max depth'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = [a : [b: ['c', 'd']]]

    when:
    ObjectVisitor.visit(target, visitor, 2, Integer.MAX_VALUE)

    then:
    1 * visitor.visit("root[]", 'a') >> CONTINUE
    1 * visitor.visit("root[a][]", 'b') >> CONTINUE
    0 * _
  }

  void 'test max objects'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = [1, 2, 3]

    when: 'we visit at most two objects'
    ObjectVisitor.visit(target, visitor, Integer.MAX_VALUE, 2)

    then: 'we visit the array and its first element'
    1 * visitor.visit("root[0]", 1) >> CONTINUE
    0 * _
  }

  void 'test cycles'() {
    given:
    final visitor = Mock(ObjectVisitor.Visitor)
    final target = new VisitableClass(name: 'cycle')
    target.cycle = target

    when: 'we visit a class with a self reference'
    ObjectVisitor.visit(target, visitor, Integer.MAX_VALUE, Integer.MAX_VALUE)

    then: 'we only visit the class once'
    1 * visitor.visit("root", target) >> CONTINUE
    1 * visitor.visit("root.name", 'cycle') >> CONTINUE
    0 * _
  }
}
