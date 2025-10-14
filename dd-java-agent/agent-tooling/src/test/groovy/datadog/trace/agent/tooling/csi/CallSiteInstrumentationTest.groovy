package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import net.bytebuddy.ByteBuddy
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.implementation.bytecode.ByteCodeAppender
import net.bytebuddy.implementation.bytecode.StackManipulation
import net.bytebuddy.implementation.bytecode.TypeCreation
import net.bytebuddy.implementation.bytecode.member.MethodInvocation
import net.bytebuddy.implementation.bytecode.member.MethodReturn
import net.bytebuddy.implementation.bytecode.member.MethodVariableAccess
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Type

import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.StackDupMode.PREPEND_ARRAY_CTOR
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AFTER
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.BEFORE

class CallSiteInstrumentationTest extends BaseCallSiteTest {

  def 'test instrumentation adds type advice'() {
    setup:
    final advice = mockCallSites(Mock(InvokeAdvice), stringConcatPointcut())
    final instrumentation = buildInstrumentation([advice])
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    def result = null
    instrumentation.typeAdvice {
      result = it.transform(builder, type, getClass().getClassLoader(), null, null)
    }

    then:
    result == builder
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test fetch advices from spi with custom class'() {
    setup:
    final instrumentation = buildInstrumentation(TestCallSites)
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    instrumentation.typeAdvice {
      it.transform(builder, type, getClass().getClassLoader(), null, null)
    }

    then:
    1 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  def 'test fetch advices from spi with no implementations'() {
    setup:
    final instrumentation = buildInstrumentation(CallSiteAdvice)
    final builder = Mock(DynamicType.Builder)
    final type = Mock(TypeDescription) {
      getName() >> StringConcatExample.name
    }

    when:
    instrumentation.typeAdvice {
      it.transform(builder, type, getClass().getClassLoader(), null, null)
    }

    then:
    0 * builder.visit(_ as AsmVisitorWrapper) >> builder
  }

  void 'test call site transformer with super call in ctor (#test)'() {
    setup:
    SuperInCtorExampleAdvice.CALLS.set(0)
    final source = Type.getType(SuperInCtorExample)
    final target = renameType(source, 'Test')
    final pointcut = stringReaderPointcut()
    final InvokeAdvice advice = new InvokeAdvice() {
        @Override
        void apply(CallSiteAdvice.MethodHandler handler, int opcode, String owner, String name, String descriptor, boolean isInterface) {
          handler.dupParameters(descriptor, PREPEND_ARRAY_CTOR)
          handler.method(opcode, owner, name, descriptor, isInterface)
          handler.advice(
            Type.getType(SuperInCtorExampleAdvice).internalName,
            'onInvoke',
            Type.getMethodType(Type.getType(StringReader), Type.getType(Object[]), Type.getType(StringReader)).getDescriptor(),
            )
        }
      }
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(AFTER, advice, pointcut)]))

    when:
    final transformedClass = transformType(source, target, callSiteTransformer)
    final transformed = loadClass(target, transformedClass)
    final reader = transformed.newInstance(param)

    then:
    reader != null
    SuperInCtorExampleAdvice.CALLS.get() > 0

    where:
    param                     | test
    "test"                    | "Operand stack underflow"
    new StringBuilder("test") | "Inconsistent stackmap frames"
  }

  void 'test new invocation without dup'() {
    setup:
    def (clazz, bytes) = buildConsumerWithNewAndNoDup()
    final source = Type.getType(clazz)
    final target = renameType(source, 'Test')
    final pointcut = stringReaderPointcut()
    final advice = Mock(InvokeAdvice)
    final callSiteTransformer = new CallSiteTransformer(mockAdvices([mockCallSites(AFTER, advice, pointcut)]))

    when:
    final transformedClass = transformType(source, bytes, target, callSiteTransformer)
    final transformed = loadClass(target, transformedClass)
    final instance = transformed.newInstance()
    instance.accept('test')

    then:
    0 * advice._
  }


  private static Tuple2<Class<?>, byte[]> buildConsumerWithNewAndNoDup() {
    final newType = new ByteBuddy()
      .subclass(Cloneable)
      .name('foo.CtorIssue')
      .modifiers(Visibility.PUBLIC)
      .defineMethod("accept", void, Visibility.PUBLIC, Ownership.MEMBER)
      .withParameters(String)
      .intercept(
      new Implementation.Simple(new ByteCodeAppender() {
        @Override
        ByteCodeAppender.Size apply(MethodVisitor mv,
          Implementation.Context ctx,
          MethodDescription method) {
          StackManipulation compound = new StackManipulation.Compound(
            TypeCreation.of(new TypeDescription.ForLoadedType(StringReader)),
            // ignore DUP opcode
            MethodVariableAccess.REFERENCE.loadFrom(1),
            MethodInvocation.invoke(new MethodDescription.ForLoadedConstructor(StringReader.getConstructor(String))),
            MethodReturn.VOID
            )
          final size = compound.apply(mv, ctx)
          return new ByteCodeAppender.Size(size.getMaximalSize(), method.getStackSize())
        }
      })
      )
      .make()
      .load(Thread.currentThread().contextClassLoader, ClassLoadingStrategy.Default.INJECTION)
    return Tuple.tuple(newType.loaded, newType.bytes)
  }

  static class StringCallSites implements CallSites, TestCallSites {

    @Override
    void accept(final Container container) {
      final pointcut = buildPointcut(String.getDeclaredMethod('concat', String))
      container.addAdvice(BEFORE, pointcut.type, pointcut.method, pointcut.descriptor, new StringConcatAdvice())
    }
  }

  static class StringConcatAdvice implements InvokeAdvice {

    @Override
    void apply(
      final MethodHandler handler,
      final int opcode,
      final String owner,
      final String name,
      final String descriptor,
      final boolean isInterface) {
      handler.method(opcode, owner, name, descriptor, isInterface)
    }
  }

  static class SuperInCtorExampleAdvice {

    private static final AtomicInteger CALLS = new AtomicInteger(0)

    static StringReader onInvoke(Object[] args, StringReader result) {
      CALLS.incrementAndGet()
      return result
    }
  }
}
