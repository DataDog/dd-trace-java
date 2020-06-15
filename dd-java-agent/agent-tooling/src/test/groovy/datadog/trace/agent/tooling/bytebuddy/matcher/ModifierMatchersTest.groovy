package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.util.test.DDSpecification
import net.bytebuddy.description.ModifierReviewable

import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.FINAL
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.NON_ABSTRACT
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.NON_FINAL
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.NON_STATIC
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.PRIVATE
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.PROTECTED
import static datadog.trace.agent.tooling.bytebuddy.matcher.ModifierMatchers.ModifierConstraint.PUBLIC
import static net.bytebuddy.jar.asm.Opcodes.ACC_FINAL
import static net.bytebuddy.jar.asm.Opcodes.ACC_PROTECTED
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC
import static net.bytebuddy.jar.asm.Opcodes.ACC_STATIC

class ModifierMatchersTest extends DDSpecification {

  static final int PUBLICSTATIC = (ACC_PUBLIC | ACC_STATIC)
  static final int PUBLICFINAL = (ACC_PUBLIC | ACC_FINAL)

    def "test anyPermittedNoForbiddenModifiers"() {
      setup:
      def modifierReviewable = Mock(ModifierReviewable)
      modifierReviewable.getModifiers() >> { modifiers }
      def matcher = ModifierMatchers.anyPermittedNoForbiddenModifiers(spec)

      when:
      def result = matcher.matches(modifierReviewable)

      then:
      result == expected

      where:
      modifiers                                      | spec                                  | expected
      PUBLICSTATIC                                   | EnumSet.of(PUBLIC, NON_STATIC)        | false
      ACC_PUBLIC                                     | EnumSet.of(PUBLIC, NON_STATIC)        | true
      ACC_PROTECTED                                  | EnumSet.of(PUBLIC, NON_STATIC)        | false
      PUBLICFINAL                                    | EnumSet.of(PUBLIC, NON_STATIC)        | true
      PublicAbstract.getModifiers()                  | EnumSet.of(PUBLIC, NON_ABSTRACT)      | false
      PublicAbstract.getModifiers()                  | EnumSet.of(PUBLIC, NON_FINAL)         | true
      PublicFinal.getModifiers()                     | EnumSet.of(PUBLIC, NON_ABSTRACT)      | true
      PublicFinal.getModifiers()                     | EnumSet.of(PUBLIC, NON_FINAL)         | true
      getMethodModifiers(Foo, "publicStatic")        | EnumSet.of(PUBLIC, NON_STATIC)        | false
      getMethodModifiers(Foo, "publicStatic")        | EnumSet.of(PRIVATE, NON_STATIC)       | false
      getMethodModifiers(Foo, "publicStatic")        | EnumSet.of(PUBLIC, NON_FINAL)         | true
      getMethodModifiers(Foo, "protectedFinal")      | EnumSet.of(PUBLIC, NON_FINAL)         | false
      getMethodModifiers(Foo, "protectedFinal")      | EnumSet.of(PUBLIC, FINAL)             | true
      getMethodModifiers(Foo, "protectedFinal")      | EnumSet.of(PROTECTED, FINAL)          | true
      getMethodModifiers(Foo, "protectedFinal")      | EnumSet.of(PROTECTED, NON_STATIC)     | true
      getMethodModifiers(Foo, "protectedFinal")      | EnumSet.of(PROTECTED, NON_FINAL)      | false
      getMethodModifiers(Foo, "protectedFinal")      | EnumSet.of(PRIVATE)                   | false
    }

  def getMethodModifiers(Class clazz, String method) {
    return clazz.getDeclaredMethod(method).getModifiers()
  }

  // note: public by default
  abstract class PublicAbstract {

  }

  class PublicFinal {

  }

  class Foo {
    static int publicStatic() {
      return 0
    }

    protected final int protectedFinal() {
      return 0
    }
  }
}
