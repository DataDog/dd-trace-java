package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteConstants.AFTER_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AFTER_ARRAY_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.ALL_ARGS_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.ARGUMENT_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AROUND_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AROUND_ARRAY_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.ASM_API_VERSION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.BEFORE_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.BEFORE_ARRAY_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.INVOKE_DYNAMIC_CONSTANTS_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.RETURN_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.THIS_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToDescriptor;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static org.objectweb.asm.ClassReader.SKIP_CODE;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import datadog.trace.plugin.csi.SpecificationBuilder;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.InvokeDynamicConstantsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ParameterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ReturnSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ThisSpecification;
import datadog.trace.plugin.csi.util.MethodType;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Implementation of {@link SpecificationBuilder} using a {@link ClassReader} to parse the Java
 * class files and build the related {@link CallSiteSpecification} instances
 */
public class AsmSpecificationBuilder implements SpecificationBuilder {

  @Override
  @Nonnull
  public Optional<CallSiteSpecification> build(@Nonnull final File file) {
    try (final InputStream stream = Files.newInputStream(file.toPath())) {
      final ClassReader reader = new ClassReader(stream);
      final SpecificationVisitor visitor = new SpecificationVisitor();
      reader.accept(visitor, SKIP_CODE | SKIP_FRAMES | SKIP_DEBUG);
      return visitor.getResult();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static class SpecificationVisitor extends ClassVisitor {

    private static final String CALL_SITE = classNameToDescriptor(CALL_SITE_ANNOTATION);

    private Type clazz;
    private boolean isCallSite;
    private final List<AdviceSpecification> advices = new ArrayList<>();
    private final Set<Type> helpers = new HashSet<>();
    private final Set<Type> spi = new HashSet<>();
    private List<String> enabled = new ArrayList<>();
    private CallSiteSpecification result;

    public SpecificationVisitor() {
      super(ASM_API_VERSION);
    }

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      clazz = classNameToType(name);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
      isCallSite = CALL_SITE.equals(descriptor);
      if (isCallSite) {
        helpers.add(clazz);
        return new AnnotationVisitor(ASM_API_VERSION) {

          @Override
          public AnnotationVisitor visitArray(final String name) {
            if ("spi".equals(name)) {
              return new AnnotationVisitor(ASM_API_VERSION) {
                @Override
                public void visit(final String name, final Object value) {
                  spi.add((Type) value);
                }
              };
            } else if ("helpers".equals(name)) {
              return new AnnotationVisitor(ASM_API_VERSION) {
                @Override
                public void visit(final String name, final Object value) {
                  helpers.add((Type) value);
                }
              };
            } else if ("enabled".equals(name)) {
              return new AnnotationVisitor(ASM_API_VERSION) {
                @Override
                public void visit(final String name, final Object value) {
                  enabled.add((String) value);
                }
              };
            }
            return null;
          }
        };
      }
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      if (isCallSite) {
        return new AdviceMethodVisitor(this, name, Type.getMethodType(descriptor));
      }
      return null;
    }

    @Override
    public void visitEnd() {
      if (isCallSite) {
        result = new CallSiteSpecification(clazz, advices, spi, enabled, helpers);
      }
    }

    public Optional<CallSiteSpecification> getResult() {
      return Optional.ofNullable(result);
    }
  }

  private static class AdviceMethodVisitor extends MethodVisitor {

    private static final Map<String, AdviceSpecificationCtor> ADVICE_BUILDERS = new HashMap<>();

    private static final Set<String> REPEATABLE_ADVICES = new HashSet<>();

    private static final Map<String, ParameterSpecificationCtor> PARAMETER_BUILDERS =
        new HashMap<>();

    static {
      ADVICE_BUILDERS.put(classNameToDescriptor(BEFORE_ANNOTATION), BeforeSpecification::new);
      ADVICE_BUILDERS.put(classNameToDescriptor(AROUND_ANNOTATION), AroundSpecification::new);
      ADVICE_BUILDERS.put(classNameToDescriptor(AFTER_ANNOTATION), AfterSpecification::new);
      REPEATABLE_ADVICES.add(classNameToDescriptor(BEFORE_ARRAY_ANNOTATION));
      REPEATABLE_ADVICES.add(classNameToDescriptor(AROUND_ARRAY_ANNOTATION));
      REPEATABLE_ADVICES.add(classNameToDescriptor(AFTER_ARRAY_ANNOTATION));
      PARAMETER_BUILDERS.put(classNameToDescriptor(THIS_ANNOTATION), ThisSpecification::new);
      PARAMETER_BUILDERS.put(
          classNameToDescriptor(ARGUMENT_ANNOTATION), ArgumentSpecification::new);
      PARAMETER_BUILDERS.put(classNameToDescriptor(RETURN_ANNOTATION), ReturnSpecification::new);
      PARAMETER_BUILDERS.put(classNameToDescriptor(ALL_ARGS_ANNOTATION), AllArgsSpecification::new);
      PARAMETER_BUILDERS.put(
          classNameToDescriptor(INVOKE_DYNAMIC_CONSTANTS_ANNOTATION),
          InvokeDynamicConstantsSpecification::new);
    }

    private final SpecificationVisitor spec;
    private final MethodType advice;
    private final Map<Integer, ParameterSpecification> parameters = new HashMap<>();
    private final Map<AdviceSpecificationCtor, List<AdviceSpecificationData>> adviceData =
        new HashMap<>();

    public AdviceMethodVisitor(
        @Nonnull final SpecificationVisitor spec,
        @Nonnull final String method,
        @Nonnull final Type methodType) {
      super(ASM_API_VERSION);
      this.spec = spec;
      advice = new MethodType(spec.clazz, method, methodType);
    }

    @Override
    public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
      AdviceSpecificationCtor ctor = ADVICE_BUILDERS.get(descriptor);
      if (ctor != null) {
        final List<AdviceSpecificationData> list =
            adviceData.computeIfAbsent(ctor, c -> new ArrayList<>());
        final AdviceSpecificationData data = new AdviceSpecificationData();
        list.add(data);
        return new AnnotationVisitor(ASM_API_VERSION) {
          @Override
          public void visit(final String key, final Object value) {
            if ("value".equals(key)) {
              data.signature = (String) value;
            } else if ("invokeDynamic".equals(key)) {
              data.invokeDynamic = (boolean) value;
            }
          }
        };
      }
      if (REPEATABLE_ADVICES.contains(descriptor)) {
        return new AnnotationVisitor(ASM_API_VERSION) {
          @Override
          public AnnotationVisitor visitArray(final String name) {
            if ("value".equals(name)) {
              return new AnnotationVisitor(ASM_API_VERSION) {
                @Override
                public AnnotationVisitor visitAnnotation(
                    final String name, final String descriptor) {
                  return AdviceMethodVisitor.this.visitAnnotation(descriptor, true);
                }
              };
            }
            return null;
          }
        };
      }
      return null;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(
        final int parameter, final String descriptor, final boolean visible) {
      if (!adviceData.isEmpty()) {
        final ParameterSpecificationCtor parameterCtor = PARAMETER_BUILDERS.get(descriptor);
        if (parameterCtor != null) {
          ParameterSpecification parameterSpec = parameterCtor.build();
          if (parameterSpec instanceof ArgumentSpecification) {
            final long index =
                parameters.values().stream()
                    .filter(it -> it instanceof ArgumentSpecification)
                    .count();
            ((ArgumentSpecification) parameterSpec)
                .setIndex((int) index); // can change in annotation visitor
          }
          parameters.put(parameter, parameterSpec);

          return new AnnotationVisitor(ASM_API_VERSION) {
            @Override
            public void visit(final String key, final Object value) {
              if ("includeThis".equals(key) && parameterSpec instanceof AllArgsSpecification) {
                final AllArgsSpecification allArgs = (AllArgsSpecification) parameterSpec;
                allArgs.setIncludeThis((boolean) value);
              } else if ("value".equals(key) && parameterSpec instanceof ArgumentSpecification) {
                ((ArgumentSpecification) parameterSpec).setIndex((Integer) value);
              }
            }
          };
        }
      }
      return null;
    }

    @Override
    public void visitEnd() {
      adviceData.forEach(
          (adviceCtor, list) ->
              list.stream()
                  .map(
                      data ->
                          adviceCtor.build(advice, parameters, data.signature, data.invokeDynamic))
                  .forEach(spec.advices::add));
    }
  }

  @FunctionalInterface
  private interface AdviceSpecificationCtor {
    AdviceSpecification build(
        @Nonnull MethodType advice,
        @Nonnull Map<Integer, ParameterSpecification> parameters,
        @Nonnull String signature,
        boolean invokeDynamic);
  }

  @FunctionalInterface
  private interface ParameterSpecificationCtor {
    ParameterSpecification build();
  }

  private static class AdviceSpecificationData {
    private String signature;
    private boolean invokeDynamic;
  }
}
