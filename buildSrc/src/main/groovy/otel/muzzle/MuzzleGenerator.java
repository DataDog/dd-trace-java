package otel.muzzle;

import static net.bytebuddy.jar.asm.Opcodes.AASTORE;
import static net.bytebuddy.jar.asm.Opcodes.ACC_FINAL;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.ACC_STATIC;
import static net.bytebuddy.jar.asm.Opcodes.ANEWARRAY;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.DUP;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESPECIAL;
import static net.bytebuddy.jar.asm.Opcodes.NEW;
import static net.bytebuddy.jar.asm.Opcodes.V1_8;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * This is a helper class to generate the Muzzle classes with its references.
 * This is a port of datadog.trace.agent.tooling.muzzle.MuzzleGenerator.
 */
public final class MuzzleGenerator {
  private MuzzleGenerator() {
  }

  private static String internalName(String originalClass) {
    return originalClass.substring(0, originalClass.length() - 6);
  }

  private static String getMuzzleFileName(String originalClass) {
    String fileName = internalName(originalClass) + "$Muzzle.class";
    int index = fileName.lastIndexOf('/');
    return fileName.substring(index + 1);
  }

  public static void writeMuzzleClass(Path target, String originalClass, List<MuzzleReference> references) throws IOException {
    Files.write(
        target.resolve(getMuzzleFileName(originalClass)),
        writeMuzzleClass(originalClass, references)
    );
  }

  private static byte[] writeMuzzleClass(String originalClass, List<MuzzleReference> references) {
    System.out.println(">>> internalName(originalClass) + \"$Muzzle\" = " + internalName(originalClass) + "$Muzzle");
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    cw.visit(
        V1_8,
        ACC_PUBLIC | ACC_FINAL,
        internalName(originalClass) + "$Muzzle",
        null,
        "java/lang/Object",
        null);

    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC | ACC_STATIC,
            "create",
            "()Ldatadog/trace/agent/tooling/muzzle/ReferenceMatcher;",
            null,
            null);

    mv.visitCode();

    mv.visitTypeInsn(NEW, "datadog/trace/agent/tooling/muzzle/ReferenceMatcher");
    mv.visitInsn(DUP);

    mv.visitLdcInsn(references.size());
    mv.visitTypeInsn(ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference");

    int i = 0;
    for (MuzzleReference reference : references) {
      mv.visitInsn(DUP);
      mv.visitLdcInsn(i++);
      writeReference(mv, reference);
      mv.visitInsn(AASTORE);
    }

    mv.visitMethodInsn(
        INVOKESPECIAL,
        "datadog/trace/agent/tooling/muzzle/ReferenceMatcher",
        "<init>",
        "([Ldatadog/trace/agent/tooling/muzzle/Reference;)V",
        false);

    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();

    return cw.toByteArray();
  }

  private static void writeReference(MethodVisitor mv, MuzzleReference reference) {
    mv.visitTypeInsn(NEW, "datadog/trace/agent/tooling/muzzle/Reference");
    mv.visitInsn(DUP);

    writeStrings(mv, reference.sources);
    mv.visitLdcInsn(reference.flags);
    mv.visitLdcInsn(reference.className);
    if (null != reference.superName) {
      mv.visitLdcInsn(reference.superName);
    } else {
      mv.visitInsn(Opcodes.ACONST_NULL);
    }
    writeStrings(mv, reference.interfaces);
    writeFields(mv, reference.fields);
    writeMethods(mv, reference.methods);

    mv.visitMethodInsn(
        INVOKESPECIAL,
        "datadog/trace/agent/tooling/muzzle/Reference",
        "<init>",
        "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
            + "[Ldatadog/trace/agent/tooling/muzzle/Reference$Field;"
            + "[Ldatadog/trace/agent/tooling/muzzle/Reference$Method;)V",
        false);
  }

  private static void writeStrings(MethodVisitor mv, List<String> strings) {
    mv.visitLdcInsn(strings.size());
    mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
    int i = 0;
    for (String string : strings) {
      mv.visitInsn(DUP);
      mv.visitLdcInsn(i++);
      mv.visitLdcInsn(string);
      mv.visitInsn(AASTORE);
    }
  }

  private static void writeFields(MethodVisitor mv, List<MuzzleReference.Field> fields) {
    mv.visitLdcInsn(fields.size());
    mv.visitTypeInsn(ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference$Field");
    int i = 0;
    for (MuzzleReference.Field field : fields) {
      mv.visitInsn(DUP);
      mv.visitLdcInsn(i++);
      mv.visitTypeInsn(NEW, "datadog/trace/agent/tooling/muzzle/Reference$Field");
      mv.visitInsn(DUP);
      writeStrings(mv, field.sources);
      mv.visitLdcInsn(field.flags);
      mv.visitLdcInsn(field.name);
      mv.visitLdcInsn(field.fieldType);
      mv.visitMethodInsn(
          INVOKESPECIAL,
          "datadog/trace/agent/tooling/muzzle/Reference$Field",
          "<init>",
          "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
          false);
      mv.visitInsn(AASTORE);
    }
  }

  private static void writeMethods(MethodVisitor mv, List<MuzzleReference.Method> methods) {
    mv.visitLdcInsn(methods.size());
    mv.visitTypeInsn(ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference$Method");
    int i = 0;
    for (MuzzleReference.Method method : methods) {
      mv.visitInsn(DUP);
      mv.visitLdcInsn(i++);
      mv.visitTypeInsn(NEW, "datadog/trace/agent/tooling/muzzle/Reference$Method");
      mv.visitInsn(DUP);
      writeStrings(mv, method.sources);
      mv.visitLdcInsn(method.flags);
      mv.visitLdcInsn(method.name);
      mv.visitLdcInsn(method.methodType);
      mv.visitMethodInsn(
          INVOKESPECIAL,
          "datadog/trace/agent/tooling/muzzle/Reference$Method",
          "<init>",
          "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
          false);
      mv.visitInsn(AASTORE);
    }
  }
}
