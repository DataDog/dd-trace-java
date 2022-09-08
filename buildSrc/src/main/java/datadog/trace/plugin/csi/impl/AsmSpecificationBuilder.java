package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteConstants.AFTER_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.ARGUMENT_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.AROUND_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.ASM_API_VERSION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.BEFORE_ANNOTATION;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ADVICE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ANNOTATION;
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
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
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
    private Type spi = classNameToType(CALL_SITE_ADVICE_CLASS); // default annotation value
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
          public void visit(final String key, final Object value) {
            if ("spi".equals(key)) {
              spi = (Type) value;
            }
          }

          @Override
          public AnnotationVisitor visitArray(final String name) {
            if ("helpers".equals(name)) {
              return new AnnotationVisitor(ASM_API_VERSION) {
                @Override
                public void visit(final String name, final Object value) {
                  helpers.add((Type) value);
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
        result = new CallSiteSpecification(clazz, advices, spi, helpers);
      }
    }

    public Optional<CallSiteSpecification> getResult() {
      return Optional.ofNullable(result);
    }
  }

  private static class AdviceMethodVisitor extends MethodVisitor {

    private static final Map<String, AdviceSpecificationCtor> ADVICE_BUILDERS = new HashMap<>();
    private static final Map<String, ParameterSpecificationCtor> PARAMETER_BUILDERS =
        new HashMap<>();

    static {
      ADVICE_BUILDERS.put(classNameToDescriptor(BEFORE_ANNOTATION), BeforeSpecification::new);
      ADVICE_BUILDERS.put(classNameToDescriptor(AROUND_ANNOTATION), AroundSpecification::new);
      ADVICE_BUILDERS.put(classNameToDescriptor(AFTER_ANNOTATION), AfterSpecification::new);
      PARAMETER_BUILDERS.put(classNameToDescriptor(THIS_ANNOTATION), ThisSpecification::new);
      PARAMETER_BUILDERS.put(
          classNameToDescriptor(ARGUMENT_ANNOTATION), ArgumentSpecification::new);
      PARAMETER_BUILDERS.put(classNameToDescriptor(RETURN_ANNOTATION), ReturnSpecification::new);
    }

    private final SpecificationVisitor spec;
    private final MethodType advice;
    private final Map<Integer, ParameterSpecification> parameters = new HashMap<>();
    private AdviceSpecificationCtor adviceCtor;
    private String signature;

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
      adviceCtor = ADVICE_BUILDERS.get(descriptor);
      if (adviceCtor != null) {
        return new AnnotationVisitor(ASM_API_VERSION) {
          @Override
          public void visit(final String key, final Object value) {
            if ("value".equals(key)) {
              signature = (String) value;
            }
          }
        };
      }
      return null;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(
        final int parameter, final String descriptor, final boolean visible) {
      if (adviceCtor != null) {
        final ParameterSpecificationCtor parameterCtor = PARAMETER_BUILDERS.get(descriptor);
        if (parameterCtor != null) {
          ParameterSpecification parameterSpec = parameterCtor.build();
          parameters.put(parameter, parameterSpec);
          return new AnnotationVisitor(ASM_API_VERSION) {
            @Override
            public void visit(final String key, final Object value) {
              if ("value".equals(key) && parameterSpec instanceof ArgumentSpecification) {
                final ArgumentSpecification argument = (ArgumentSpecification) parameterSpec;
                argument.setIndex((int) value);
              }
            }
          };
        }
      }
      return null;
    }

    @Override
    public void visitEnd() {
      if (adviceCtor != null) {
        AdviceSpecification specification = adviceCtor.build(advice, parameters, signature);
        spec.advices.add(specification);
      }
    }
  }

  @FunctionalInterface
  private interface AdviceSpecificationCtor {
    AdviceSpecification build(
        @Nonnull MethodType advice,
        @Nonnull Map<Integer, ParameterSpecification> parameters,
        @Nonnull String signature);
  }

  @FunctionalInterface
  private interface ParameterSpecificationCtor {
    ParameterSpecification build();
  }
}
