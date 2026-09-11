package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/** Generates a {@code $Muzzle} side class from resolved references. */
final class MuzzleGenerator {
  private MuzzleGenerator() {}

  static void generate(
      File targetDirectory, InstrumenterModule module, List<Reference> references) {
    File muzzleClass =
        new File(targetDirectory, Type.getInternalName(module.getClass()) + "$Muzzle.class");
    try {
      muzzleClass.getParentFile().mkdirs();
      Files.write(muzzleClass.toPath(), generateMuzzleClass(module, references));
    } catch (IOException error) {
      throw new IllegalStateException(
          "Cannot write muzzle class for " + module.getClass().getName(), error);
    }
  }

  private static byte[] generateMuzzleClass(InstrumenterModule module, List<Reference> references) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    cw.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
        Type.getInternalName(module.getClass()) + "$Muzzle",
        null,
        "java/lang/Object",
        null);

    MethodVisitor mv =
        cw.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "create",
            "()Ldatadog/trace/agent/tooling/muzzle/ReferenceMatcher;",
            null,
            null);
    mv.visitCode();

    mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/ReferenceMatcher");
    mv.visitInsn(Opcodes.DUP);

    mv.visitLdcInsn(references.size());
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference");

    int i = 0;
    for (Reference reference : references) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitLdcInsn(i++);
      writeReference(mv, reference);
      mv.visitInsn(Opcodes.AASTORE);
    }

    mv.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "datadog/trace/agent/tooling/muzzle/ReferenceMatcher",
        "<init>",
        "([Ldatadog/trace/agent/tooling/muzzle/Reference;)V",
        false);
    mv.visitInsn(Opcodes.ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();

    return cw.toByteArray();
  }

  private static void writeReference(MethodVisitor mv, Reference reference) {
    if (reference instanceof OrReference) {
      mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/OrReference");
      mv.visitInsn(Opcodes.DUP);
    }

    mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/Reference");
    mv.visitInsn(Opcodes.DUP);

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
        Opcodes.INVOKESPECIAL,
        "datadog/trace/agent/tooling/muzzle/Reference",
        "<init>",
        "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
            + "[Ldatadog/trace/agent/tooling/muzzle/Reference$Field;"
            + "[Ldatadog/trace/agent/tooling/muzzle/Reference$Method;)V",
        false);

    if (reference instanceof OrReference) {
      Reference[] ors = ((OrReference) reference).ors;

      mv.visitLdcInsn(ors.length);
      mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference");

      int i = 0;
      for (Reference or : ors) {
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(i++);
        writeReference(mv, or);
        mv.visitInsn(Opcodes.AASTORE);
      }

      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          "datadog/trace/agent/tooling/muzzle/OrReference",
          "<init>",
          "(Ldatadog/trace/agent/tooling/muzzle/Reference;"
              + "[Ldatadog/trace/agent/tooling/muzzle/Reference;)V",
          false);
    }
  }

  private static void writeStrings(MethodVisitor mv, String[] strings) {
    mv.visitLdcInsn(strings.length);
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
    int i = 0;
    for (String string : strings) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitLdcInsn(i++);
      mv.visitLdcInsn(string);
      mv.visitInsn(Opcodes.AASTORE);
    }
  }

  private static void writeFields(MethodVisitor mv, Reference.Field[] fields) {
    mv.visitLdcInsn(fields.length);
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference$Field");
    int i = 0;
    for (Reference.Field field : fields) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitLdcInsn(i++);
      mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/Reference$Field");
      mv.visitInsn(Opcodes.DUP);
      writeStrings(mv, field.sources);
      mv.visitLdcInsn(field.flags);
      mv.visitLdcInsn(field.name);
      mv.visitLdcInsn(field.fieldType);
      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          "datadog/trace/agent/tooling/muzzle/Reference$Field",
          "<init>",
          "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
          false);
      mv.visitInsn(Opcodes.AASTORE);
    }
  }

  private static void writeMethods(MethodVisitor mv, Reference.Method[] methods) {
    mv.visitLdcInsn(methods.length);
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "datadog/trace/agent/tooling/muzzle/Reference$Method");
    int i = 0;
    for (Reference.Method method : methods) {
      mv.visitInsn(Opcodes.DUP);
      mv.visitLdcInsn(i++);
      mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/muzzle/Reference$Method");
      mv.visitInsn(Opcodes.DUP);
      writeStrings(mv, method.sources);
      mv.visitLdcInsn(method.flags);
      mv.visitLdcInsn(method.name);
      mv.visitLdcInsn(method.methodType);
      mv.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          "datadog/trace/agent/tooling/muzzle/Reference$Method",
          "<init>",
          "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
          false);
      mv.visitInsn(Opcodes.AASTORE);
    }
  }
}
