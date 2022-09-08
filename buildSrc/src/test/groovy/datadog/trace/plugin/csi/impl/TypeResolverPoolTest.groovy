package datadog.trace.plugin.csi.impl

import org.objectweb.asm.Type
import spock.lang.Specification

class TypeResolverPoolTest extends Specification {

  def 'test resolve primitive'() {
    setup:
    final resolver = new TypeResolverPool()

    when:
    final result = resolver.resolveType(Type.INT_TYPE)

    then:
    result == int.class
  }

  def 'test resolve primitive array'() {
    setup:
    final resolver = new TypeResolverPool()
    final type = Type.getType('[I')

    when:
    final result = resolver.resolveType(type)

    then:
    result == int[].class
  }

  def 'test resolve primitive multidimensional array'() {
    setup:
    final resolver = new TypeResolverPool()
    final type = Type.getType('[[[I')

    when:
    final result = resolver.resolveType(type)

    then:
    result == int[][][].class
  }

  def 'test resolve class'() {
    setup:
    final resolver = new TypeResolverPool()
    final type = Type.getType(String)

    when:
    final result = resolver.resolveType(type)

    then:
    result == String
  }


  def 'test resolve class array'() {
    setup:
    final resolver = new TypeResolverPool()
    final type = Type.getType(String[])

    when:
    final result = resolver.resolveType(type)

    then:
    result == String[]
  }

  def 'test resolve class multidimensional array'() {
    setup:
    final resolver = new TypeResolverPool()
    final type = Type.getType(String[][][])

    when:
    final result = resolver.resolveType(type)

    then:
    result == String[][][]
  }

  def 'test type resolver from method'() {
    setup:
    final resolver = new TypeResolverPool()
    final type = Type.getMethodType(Type.getType(String[]), Type.getType(String), Type.getType(String))

    when:
    final result = resolver.resolveType(type.getReturnType())

    then:
    result == String[]
  }
}
