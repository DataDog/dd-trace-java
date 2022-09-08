package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.api.function.BiFunction
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

class CallSiteTransformerTest extends BaseCallSiteTest {

  def 'test call site transformer'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'Test')
    final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))
    final callSite = mockAdvice(pointcut)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([callSite]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply("Hello ", "World!")

    then:
    1 * callSite.apply(_ as MethodVisitor, Opcodes.INVOKEVIRTUAL, pointcut.type(), pointcut.method(), pointcut.descriptor(), false) >> { params ->
      final args = params as Object[]
      final mv = args[0] as MethodVisitor
      mv.visitInsn(Opcodes.SWAP)
      mv.visitInsn(Opcodes.POP)
      mv.visitLdcInsn("Goodbye ")
      mv.visitInsn(Opcodes.SWAP)
      mv.visitMethodInsn(args[1] as int, args[2] as String, args[3] as String, args[4] as String, args[5] as Boolean)
    }
    result == "Goodbye World!"
  }

  def 'test call site with non matching advice'() {
    setup:
    final source = Type.getType(StringConcatExample)
    final target = renameType(source, 'TestNoAdvices')
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final instance = loadType(target, transformedClass) as BiFunction<String, String, String>
    final result = instance.apply("Hello ", "World!")

    then:
    result == "Hello World!"
  }

  private static Type renameType(final Type sourceType, final String suffix) {
    return Type.getType(sourceType.descriptor.replace('StringConcatExample', "StringConcatExample${suffix}"))
  }
}
