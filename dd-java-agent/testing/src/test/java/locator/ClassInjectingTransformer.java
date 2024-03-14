package locator;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.jar.asm.Opcodes.ACC_ABSTRACT;
import static net.bytebuddy.jar.asm.Opcodes.ACC_INTERFACE;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.V1_8;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

public class ClassInjectingTransformer implements AgentBuilder.Transformer, AsmVisitorWrapper {

  private static final String BINARY_NAME = "locator/InjectedInterface";
  public static final String NAME = BINARY_NAME.replace("/", ".");

  public static AgentBuilder instrument(AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named(ClassInjectingTestInstrumentation.class.getName() + "$ToBeInstrumented"))
        .transform(new ClassInjectingTransformer());
  }

  public static void injectInterfaceNamed(String binaryName, ClassLoader classLoader) {
    MethodHandles.Lookup myLookup = MethodHandles.lookup();
    try {
      Method m =
          ClassLoader.class.getDeclaredMethod(
              "defineClass",
              String.class,
              byte[].class,
              Integer.TYPE,
              Integer.TYPE,
              ProtectionDomain.class);
      m.setAccessible(true);
      MethodHandle defineMethod = myLookup.unreflect(m);
      ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
      cw.visit(
          V1_8,
          ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
          binaryName,
          null,
          "java/lang/Object",
          null);
      String markerName = ClassInjectingTestInstrumentation.class.getName() + "$ToBeMatched";
      cw.visitAnnotation("L" + markerName.replace(".", "/") + ";", true).visitEnd();
      byte[] bytes = cw.toByteArray();
      defineMethod.invoke(classLoader, binaryName.replace("/", "."), bytes, 0, bytes.length, null);
    } catch (Throwable e) {
      e.printStackTrace();
    }
  }

  @Override
  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain pd) {

    // First we create an interface and define it
    injectInterfaceNamed(BINARY_NAME, classLoader);

    // Then we let the visitor add it to the class
    return builder.visit(this);
  }

  @Override
  public int mergeWriter(int flags) {
    return flags;
  }

  @Override
  public int mergeReader(int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    return new ClassVisitor(Opcodes.ASM7, classVisitor) {
      @Override
      public void visit(
          final int version,
          final int access,
          final String name,
          final String signature,
          final String superName,
          final String[] interfaces) {
        List<String> ifs = interfaces != null ? Arrays.asList(interfaces) : new ArrayList<String>();
        ifs.add(BINARY_NAME);
        super.visit(version, access, name, signature, superName, ifs.toArray(new String[0]));
      }
    };
  }
}
