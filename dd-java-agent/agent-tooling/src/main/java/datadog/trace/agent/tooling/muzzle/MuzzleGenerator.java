package datadog.trace.agent.tooling.muzzle;

import static java.util.Arrays.asList;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.HelperScanner;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

/** Generates a 'Muzzle' side-class for each {@link InstrumenterModule}. */
public class MuzzleGenerator implements AsmVisitorWrapper {
  private final File targetDir;

  public MuzzleGenerator(File targetDir) {
    this.targetDir = targetDir;
  }

  @Override
  public int mergeWriter(int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      final TypeDescription moduleDefinition,
      final ClassVisitor classVisitor,
      final Implementation.Context implementationContext,
      final TypePool typePool,
      final FieldList<FieldDescription.InDefinedShape> fields,
      final MethodList<?> methods,
      final int writerFlags,
      final int readerFlags) {

    InstrumenterModule module;
    try {
      module =
          (InstrumenterModule)
              Thread.currentThread()
                  .getContextClassLoader()
                  .loadClass(moduleDefinition.getName())
                  .getConstructor()
                  .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }

    File muzzleClass = new File(targetDir, moduleDefinition.getInternalName() + "$Muzzle.class");
    try {
      muzzleClass.getParentFile().mkdirs();
      Files.write(muzzleClass.toPath(), generateMuzzleClass(module));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return classVisitor;
  }

  private static Reference[] generateReferences(
      Instrumenter.HasMethodAdvice instrumenter,
      AdviceShader adviceShader,
      Set<String> allAdviceClasses) {
    // track sources we've generated references from to avoid recursion
    final Set<String> referenceSources = new HashSet<>();
    final Map<String, Reference> references = new LinkedHashMap<>();
    final Set<String> adviceClasses = new HashSet<>();
    instrumenter.methodAdvice(
        (matcher, adviceClass, additionalClasses) -> {
          adviceClasses.add(adviceClass);
          if (additionalClasses != null) {
            adviceClasses.addAll(asList(additionalClasses));
          }
        });
    // remember the advice roots so callers can exclude them from the injected helper set
    allAdviceClasses.addAll(adviceClasses);
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    for (String adviceClass : adviceClasses) {
      if (referenceSources.add(adviceClass)) {
        for (Map.Entry<String, Reference> entry :
            ReferenceCreator.createReferencesFrom(adviceClass, adviceShader, contextClassLoader)
                .entrySet()) {
          Reference toMerge = references.get(entry.getKey());
          if (null == toMerge) {
            references.put(entry.getKey(), entry.getValue());
          } else {
            references.put(entry.getKey(), toMerge.merge(entry.getValue()));
          }
        }
      }
    }
    return references.values().toArray(new Reference[0]);
  }

  /** This code is generated in a separate side-class. */
  private byte[] generateMuzzleClass(InstrumenterModule module) {

    AdviceShader adviceShader = AdviceShader.with(module.adviceShading());
    HelperClassPredicate helperPredicate = new HelperClassPredicate(this::isOwnOutput);

    // Crawl advice for muzzle references (only recursing into the instrumentation package).
    Set<String> adviceClasses = new HashSet<>();
    List<Reference> allReferences = new ArrayList<>();
    for (Instrumenter instrumenter : module.typeInstrumentations()) {
      if (instrumenter instanceof Instrumenter.HasMethodAdvice) {
        Collections.addAll(
            allReferences,
            generateReferences(
                (Instrumenter.HasMethodAdvice) instrumenter, adviceShader, adviceClasses));
      }
    }

    // Inferred helpers = our classes referenced from the advice minus the advice roots.
    Set<String> inferredHelpers = new LinkedHashSet<>();
    for (Reference reference : allReferences) {
      if (!adviceClasses.contains(reference.className)
          && helperPredicate.isHelperClass(reference.className)) {
        inferredHelpers.add(reference.className);
      }
    }

    // Manual additions cover helpers the crawl can't see.
    Set<String> manualHelpers = new LinkedHashSet<>(asList(module.helperClassNames()));
    Set<String> seedHelpers = new LinkedHashSet<>(inferredHelpers);
    seedHelpers.addAll(manualHelpers);
    for (String helper : new ArrayList<>(seedHelpers)) {
      if (isOwnOutput(helper)) {
        addNestedClasses(helper, seedHelpers);
      }
    }

    String[] orderedHelpers =
        discoverAndOrderHelpers(
            seedHelpers,
            manualHelpers,
            helperPredicate,
            Thread.currentThread().getContextClassLoader());

    writeInferenceReport(module, adviceClasses.isEmpty(), inferredHelpers, orderedHelpers);

    // Injected helpers are our own classes, so they don't need to be asserted as library
    // references.
    Set<String> ignoredClassNames = new HashSet<>(asList(orderedHelpers));
    Collections.addAll(ignoredClassNames, module.muzzleIgnoredClassNames());
    List<Reference> references = new ArrayList<>();
    for (Reference reference : allReferences) {
      if (!ignoredClassNames.contains(reference.className)) {
        references.add(reference);
      }
    }
    Reference[] additionalReferences = module.additionalMuzzleReferences();
    if (null != additionalReferences) {
      Collections.addAll(references, additionalReferences);
    }

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

    // Generated: public static String[] helperClassNames() — omitted for helper-less modules.
    if (orderedHelpers.length > 0) {
      MethodVisitor hv =
          cw.visitMethod(
              Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
              "helperClassNames",
              "()[Ljava/lang/String;",
              null,
              null);
      hv.visitCode();
      writeStrings(hv, orderedHelpers);
      hv.visitInsn(Opcodes.ARETURN);
      hv.visitMaxs(0, 0);
      hv.visitEnd();
    }

    return cw.toByteArray();
  }

  /** {@code true} if the class was compiled from this instrumentation subproject's own output. */
  private boolean isOwnOutput(String className) {
    return new File(targetDir, className.replace('.', '/') + ".class").isFile();
  }

  /** Adds the nested classes ({@code Foo$Bar}, {@code Foo$1}, ...) of an own-output helper. */
  private void addNestedClasses(String className, Set<String> helperClasses) {
    File classFile = new File(targetDir, className.replace('.', '/') + ".class");
    File dir = classFile.getParentFile();
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    int lastDot = className.lastIndexOf('.');
    String pkg = lastDot < 0 ? "" : className.substring(0, lastDot + 1);
    String prefix = (lastDot < 0 ? className : className.substring(lastDot + 1)) + "$";
    File[] siblings = dir.listFiles();
    if (siblings == null) {
      return;
    }
    for (File sibling : siblings) {
      String fileName = sibling.getName();
      if (fileName.startsWith(prefix) && fileName.endsWith(".class")) {
        helperClasses.add(pkg + fileName.substring(0, fileName.length() - ".class".length()));
      }
    }
  }

  /**
   * Runs {@link HelperScanner} over the seed helpers to both discover their transitive helper
   * dependencies and load-order the result (dependencies first). Keeps only our own helpers (plus
   * manual additions), dropping library classes the scanner pulls in, and retains every seed even
   * if it could not be located.
   */
  private static String[] discoverAndOrderHelpers(
      Set<String> seedHelpers,
      Set<String> manualHelpers,
      HelperClassPredicate helperPredicate,
      ClassLoader loader) {
    if (seedHelpers.isEmpty()) {
      return new String[0];
    }
    List<String> ordered = new ArrayList<>();
    try {
      for (String name :
          HelperScanner.withClassDependencies(
              ClassFileLocator.ForClassLoader.of(loader), seedHelpers.toArray(new String[0]))) {
        if ((helperPredicate.isHelperClass(name) || manualHelpers.contains(name))
            && !ordered.contains(name)) {
          ordered.add(name);
        }
      }
    } catch (Throwable ignore) {
      // best-effort ordering; unordered seeds are appended below
    }
    for (String seed : seedHelpers) {
      if (!ordered.contains(seed)) {
        ordered.add(seed);
      }
    }
    return ordered.toArray(new String[0]);
  }

  /**
   * Writes an advisory report classifying each declared helper as inferred vs. manual-only, to
   * guide migration. Best-effort — never fails the build.
   */
  private void writeInferenceReport(
      InstrumenterModule module,
      boolean adviceLess,
      Set<String> inferredHelpers,
      String[] injectedHelpers) {
    try {
      Set<String> declared = new LinkedHashSet<>(asList(module.helperClassNames()));
      if (declared.isEmpty() && inferredHelpers.isEmpty()) {
        return;
      }
      File buildDir = targetDir;
      while (buildDir != null && !"build".equals(buildDir.getName())) {
        buildDir = buildDir.getParentFile();
      }
      if (buildDir == null) {
        return;
      }
      File reportDir = new File(buildDir, "reports/helper-inference");
      reportDir.mkdirs();
      StringBuilder sb = new StringBuilder();
      sb.append("module: ").append(module.getClass().getName()).append('\n');
      sb.append("has-advice: ").append(!adviceLess).append('\n');
      sb.append("injected-helpers: ").append(injectedHelpers.length).append('\n');
      if (adviceLess && !declared.isEmpty()) {
        sb.append("crawl-cannot-cover: advice-less module; declared helpers are all manual-only\n");
      }
      for (String name : declared) {
        sb.append(inferredHelpers.contains(name) ? "  inferred     " : "  manual-only  ")
            .append(name)
            .append('\n');
      }
      for (String name : inferredHelpers) {
        if (!declared.contains(name)) {
          sb.append("  inferred-only ").append(name).append('\n');
        }
      }
      Files.write(
          new File(reportDir, module.getClass().getName() + ".txt").toPath(),
          sb.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Throwable ignore) {
      // report is advisory; never fail the build over it
    }
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
