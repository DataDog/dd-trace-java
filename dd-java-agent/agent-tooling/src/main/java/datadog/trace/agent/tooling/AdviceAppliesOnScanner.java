package datadog.trace.agent.tooling;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans an {@link InstrumenterModule} to extract, custom {@link
 * datadog.trace.agent.tooling.annotation.AppliesOn} annotation in any advices that are applied.
 */
public final class AdviceAppliesOnScanner {
  private static final Logger log = LoggerFactory.getLogger(AdviceAppliesOnScanner.class);

  private static final String APPLIESON_ANNOTATION_DESC =
      "Ldatadog/trace/agent/tooling/annotation/AppliesOn;";

  public static Map<String, Set<InstrumenterModule.TargetSystem>> extractCustomAdvices(
      Instrumenter instrumenter) throws IOException {
    if (!(instrumenter instanceof Instrumenter.HasMethodAdvice)) {
      return emptyMap();
    }
    final String instrumenterClassName = instrumenter.getClass().getName();
    log.debug("Processing instrumenter class: {}", instrumenterClassName);
    final Map<String, Set<InstrumenterModule.TargetSystem>> map = new HashMap<>();
    final Set<InstrumenterModule.TargetSystem> overriddenTargetSystems =
        EnumSet.noneOf(InstrumenterModule.TargetSystem.class);
    // collect the advices
    final Set<String> adviceClassNames = new HashSet<>();
    ((Instrumenter.HasMethodAdvice) instrumenter)
        .methodAdvice(
            (matcher, adviceClass, additionalClasses) -> {
              adviceClassNames.add(adviceClass);
              if (additionalClasses != null) {
                adviceClassNames.addAll(asList(additionalClasses));
              }
            });
    for (String adviceClassName : adviceClassNames) {
      // process each advice
      new ClassReader(adviceClassName)
          .accept(
              new ClassVisitor(Opcodes.ASM8) {
                private String className;

                @Override
                public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces) {
                  className = name.replace('/', '.');
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                  if (APPLIESON_ANNOTATION_DESC.equals(descriptor)) {
                    return new AnnotationVisitor(Opcodes.ASM8) {

                      @Override
                      public AnnotationVisitor visitArray(String name) {
                        if ("targetSystems".equals(name)) {
                          return new AnnotationVisitor(Opcodes.ASM8) {
                            @Override
                            public void visitEnum(String name, String descriptor, String value) {
                              try {
                                overriddenTargetSystems.add(
                                    InstrumenterModule.TargetSystem.valueOf(value));
                              } catch (IllegalArgumentException e) {
                                log.warn("Unknown target system: {}", value);
                              }
                            }
                          };
                        }
                        return null;
                      }

                      @Override
                      public void visitEnd() {
                        if (!overriddenTargetSystems.isEmpty()) {
                          log.debug(
                              "Found @AppliesOn on {} → {}", className, overriddenTargetSystems);
                          map.put(
                              className.substring(className.lastIndexOf('.') + 1),
                              overriddenTargetSystems);
                        }
                      }
                    };
                  }
                  return null;
                }
              },
              ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    return map;
  }
}
