package datadog.trace.agent.tooling.bytebuddy.profiling;

import static net.bytebuddy.utility.OpenedClassReader.ASM_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.signature.SignatureReader;
import net.bytebuddy.jar.asm.signature.SignatureVisitor;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

class UnwrappingVisitorTest {

  @Test
  void testGenericSignatureIsKeptInSync() throws IOException {
    // setup
    byte[] classFile = readClassBytes(FutureTask.class);
    ClassReader classReader = new ClassReader(classFile);
    ClassWriter classWriter = new ClassWriter(classReader, 0);

    List<String> declaredInterfaces = new ArrayList<>();
    List<String> genericInterfaces = new ArrayList<>();

    // visitor that captures interface types defined inside a generic type signature
    SignatureVisitor signatureVisitor =
        new SignatureVisitor(ASM_API) {
          @Override
          public SignatureVisitor visitInterface() {
            return new SignatureVisitor(ASM_API) {
              @Override
              public void visitClassType(String name) {
                genericInterfaces.add(name);
              }
            };
          }
        };

    // visitor that captures declared and generic interface types
    ClassVisitor classVisitor =
        new ClassVisitor(ASM_API, classWriter) {
          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            declaredInterfaces.addAll(Arrays.asList(interfaces));
            new SignatureReader(signature).accept(signatureVisitor);
            super.visit(version, access, name, signature, superName, interfaces);
          }
        };

    // apply TaskWrapper transformation and check both declared and generic interfaces are updated
    UnwrappingVisitor.ImplementTaskWrapperClassVisitor taskVisitor =
        new UnwrappingVisitor.ImplementTaskWrapperClassVisitor(
            classVisitor, "java.util.concurrent.FutureTask", "callable");

    // when
    classReader.accept(taskVisitor, 0);

    // then
    assertEquals(declaredInterfaces, genericInterfaces);
    assertEquals(
        "datadog/trace/bootstrap/instrumentation/api/TaskWrapper",
        genericInterfaces.get(genericInterfaces.size() - 1));
  }

  static byte[] readClassBytes(Class<?> clazz) throws IOException {
    String classResourceName = "/" + clazz.getName().replace('.', '/') + ".class";
    try (InputStream classStream = clazz.getResourceAsStream(classResourceName)) {
      assertNotNull(classStream, "Could not find class resource: " + classResourceName);
      return IOUtils.toByteArray(classStream);
    }
  }
}
