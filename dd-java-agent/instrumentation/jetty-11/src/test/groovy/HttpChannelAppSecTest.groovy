import datadog.trace.agent.test.InstrumentationSpecification
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.utility.OpenedClassReader
import org.eclipse.jetty.server.HttpChannel

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.IllegalClassFormatException
import java.security.ProtectionDomain

class HttpChannelAppSecTest extends InstrumentationSpecification {

  void 'test blocking capabilities in HttpChannel'() {
    given:
    def target = HttpChannel
    def name = target.name.replaceAll('\\.', '/')
    def visitor = new BlockingClassVisitor()
    def transformer = new ClassFileTransformer() {
        @Override
        byte[] transform(ClassLoader loader,
          String className,
          Class<?> classBeingRedefined,
          ProtectionDomain protectionDomain,
          byte[] classfileBuffer) throws IllegalClassFormatException {
          if (name == className) {
            final reader = new ClassReader(classfileBuffer)
            reader.accept(visitor, 0)
          }
          return classfileBuffer
        }
      }

    when:
    INSTRUMENTATION.addTransformer(transformer, true)
    INSTRUMENTATION.retransformClasses(target)

    then:
    visitor.blockApplied

    cleanup:
    INSTRUMENTATION.removeTransformer(transformer)
  }

  private static class BlockingClassVisitor extends ClassVisitor {

    private BlockingMethodVisitor methodVisitor

    protected BlockingClassVisitor() {
      super(OpenedClassReader.ASM_API)
    }

    @Override
    MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
      if ('handle' == name && '()Z' == descriptor) {
        return methodVisitor = new BlockingMethodVisitor()
      }
      return super.visitMethod(access, name, descriptor, signature, exceptions)
    }

    boolean getBlockApplied() {
      return methodVisitor?.blockApplied
    }
  }

  private static class BlockingMethodVisitor extends MethodVisitor {

    boolean blockApplied = false

    protected BlockingMethodVisitor() {
      super(OpenedClassReader.ASM_API)
    }

    @Override
    void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if ('datadog/trace/instrumentation/jetty/JettyBlockingHelper' == owner && 'hasRequestBlockingAction' == name) {
        blockApplied = true
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
  }
}
