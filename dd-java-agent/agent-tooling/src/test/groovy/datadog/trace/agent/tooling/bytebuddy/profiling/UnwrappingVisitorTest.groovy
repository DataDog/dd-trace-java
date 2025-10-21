package datadog.trace.agent.tooling.bytebuddy.profiling

import static net.bytebuddy.utility.OpenedClassReader.ASM_API

import java.util.concurrent.FutureTask
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.signature.SignatureReader
import net.bytebuddy.jar.asm.signature.SignatureVisitor
import org.apache.commons.io.IOUtils
import spock.lang.Specification

class UnwrappingVisitorTest extends Specification {

  void 'test generic signature is kept in sync'(){
    setup:
    def classFile = readClassBytes(FutureTask)
    def classReader = new ClassReader(classFile)
    def classWriter = new ClassWriter(classReader, 0)

    def declaredInterfaces = []
    def genericInterfaces = []

    // visitor that captures interface types defined inside a generic type signature
    def signatureVisitor = new SignatureVisitor(ASM_API) {
        @Override
        SignatureVisitor visitInterface() {
          return new SignatureVisitor(ASM_API) {
              @Override
              void visitClassType(String name) {
                genericInterfaces += name
              }
            }
        }
      }

    // visitor that captures declared and generic interface types
    def classVisitor = new ClassVisitor(ASM_API, classWriter) {
        @Override
        void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
          declaredInterfaces = interfaces
          new SignatureReader(signature).accept(signatureVisitor)
          super.visit(version, access, name, signature, superName, interfaces)
        }
      }

    // apply TaskWrapper transformation and check both declared and generic interfaces are updated
    def taskVisitor = new UnwrappingVisitor.ImplementTaskWrapperClassVisitor(
      classVisitor, 'java.util.concurrent.FutureTask', 'callable')

    when:
    classReader.accept(taskVisitor, 0)

    then:
    genericInterfaces == declaredInterfaces
    genericInterfaces.last() == 'datadog/trace/bootstrap/instrumentation/api/TaskWrapper'
  }

  static byte [] readClassBytes(Class<?> clazz){
    final String classResourceName = '/' + clazz.getName().replace('.', '/') + '.class'
    try (InputStream is = clazz.getResourceAsStream(classResourceName)) {
      if(is == null) {
        throw new IllegalStateException("Could not find class resource: " + classResourceName)
      }
      return IOUtils.toByteArray(is)
    }
  }
}
