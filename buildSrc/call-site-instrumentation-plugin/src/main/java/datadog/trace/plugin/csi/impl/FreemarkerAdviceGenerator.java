package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver;
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER;
import static datadog.trace.plugin.csi.util.CallSiteUtils.capitalize;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static datadog.trace.plugin.csi.util.CallSiteUtils.createNewFile;
import static datadog.trace.plugin.csi.util.CallSiteUtils.deleteFile;

import datadog.trace.plugin.csi.AdviceGenerator;
import datadog.trace.plugin.csi.AdvicePointcutParser;
import datadog.trace.plugin.csi.HasErrors;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.ValidationContext;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/**
 * Implementation of {@link AdviceGenerator} that uses Freemarker to build the Java files with the
 * {@link datadog.trace.agent.tooling.csi.CallSiteAdvice} implementation
 */
public class FreemarkerAdviceGenerator implements AdviceGenerator {

  private static final int APPLY_METHOD_BODY_BUFFER = 512;
  private static final String TAB = "  ";
  private static final char LINE_END = '\n';

  private final File targetFolder;
  private final AdvicePointcutParser pointcutParser;
  private final TypeResolver typeResolver;

  private final Template template;

  public FreemarkerAdviceGenerator(
      @Nonnull final File targetFolder, @Nonnull final AdvicePointcutParser pointcutParser) {
    this(targetFolder, pointcutParser, typeResolver());
  }

  public FreemarkerAdviceGenerator(
      @Nonnull final File targetFolder,
      @Nonnull final AdvicePointcutParser pointcutParser,
      @Nonnull final TypeResolver typeResolver) {
    this.targetFolder = targetFolder;
    this.pointcutParser = pointcutParser;
    this.typeResolver = typeResolver;
    template = createTemplate();
  }

  @Override
  @Nonnull
  public CallSiteResult generate(@Nonnull final CallSiteSpecification spec) {
    final CallSiteResult result = new CallSiteResult(spec);
    result.addContextProperty(TYPE_RESOLVER, typeResolver);
    try {
      spec.validate(result);
      if (result.isSuccess()) {
        Map<String, List<AdviceSpecification>> advices = groupAdvicesByMethod(spec);
        for (List<AdviceSpecification> list : advices.values()) {
          final boolean unique = list.size() == 1;
          for (int i = 0; i < list.size(); i++) {
            final AdviceSpecification advice = list.get(i);
            final String className =
                String.format(
                    "%s%s%s",
                    spec.getClazz().getClassName(),
                    capitalize(advice.getAdvice().getMethodName()),
                    unique ? "" : i);
            result.addAdvice(
                generateAdviceJavaFile(
                    spec.getSpi(), spec.getHelpers(), advice, classNameToType(className)));
          }
        }
      }
    } catch (Throwable e) {
      handleThrowable(result, e);
    }
    return result;
  }

  private Map<String, List<AdviceSpecification>> groupAdvicesByMethod(
      @Nonnull final CallSiteSpecification spec) {
    return spec.getAdvices()
        .collect(Collectors.groupingBy(advice -> advice.getAdvice().getMethodName()));
  }

  private AdviceResult generateAdviceJavaFile(
      @Nonnull final Type spiClass,
      @Nonnull final Type[] helperClasses,
      @Nonnull final AdviceSpecification spec,
      @Nonnull final Type adviceClass) {
    final File javaFile = new File(targetFolder, adviceClass.getInternalName() + ".java");
    final AdviceResult result = new AdviceResult(spec, javaFile);
    result.addContextProperty(TYPE_RESOLVER, typeResolver);
    createNewFile(javaFile);
    try (Writer writer = new FileWriter(javaFile)) {
      spec.parseSignature(pointcutParser);
      spec.validate(result);
      if (!result.isSuccess()) {
        deleteFile(javaFile);
        return result;
      }
      final Map<String, Object> arguments = new HashMap<>();
      arguments.put("spiPackageName", getPackageName(spiClass));
      arguments.put("spiClassName", getClassName(spiClass, false));
      arguments.put("packageName", getPackageName(adviceClass));
      arguments.put("className", getClassName(adviceClass));
      arguments.put("dynamicInvoke", spec.isInvokeDynamic());
      arguments.put("computeMaxStack", spec.isComputeMaxStack());
      arguments.put("applyBody", getApplyMethodBody(spec));
      arguments.put("helperClassNames", getHelperClassNames(helperClasses));
      final MethodType pointcut = spec.getPointcut();
      arguments.put("type", pointcut.getOwner().getInternalName());
      arguments.put("method", pointcut.getMethodName());
      arguments.put("methodDescriptor", pointcut.getMethodType().getDescriptor());
      template.process(arguments, writer);
    } catch (Throwable e) {
      deleteFile(javaFile);
      handleThrowable(result, e);
    }
    return result;
  }

  private Set<String> getHelperClassNames(final Type[] spec) {
    return Arrays.stream(spec).map(Type::getClassName).collect(Collectors.toSet());
  }

  private String getApplyMethodBody(@Nonnull final AdviceSpecification spec) {
    final StringBuilder builder = new StringBuilder(APPLY_METHOD_BODY_BUFFER);
    if (spec instanceof BeforeSpecification) {
      writeStackOperations(builder, spec);
      writeAdviceMethodCall(builder, spec);
      writeOriginalMethodCall(builder, spec);
    } else if (spec instanceof AfterSpecification) {
      writeStackOperations(builder, spec);
      writeOriginalMethodCall(builder, spec);
      writeAdviceMethodCall(builder, spec);
    } else {
      writeAdviceMethodCall(builder, spec);
    }
    return builder.toString();
  }

  private void writeStackOperations(
      @Nonnull final StringBuilder builder, @Nonnull final AdviceSpecification advice) {
    final AllArgsSpecification allArgsSpec = advice.findAllArguments();
    final String mode;
    if (allArgsSpec != null) {
      mode = advice instanceof AfterSpecification ? "PREPEND_ARRAY" : "APPEND_ARRAY";
    } else {
      mode = "COPY";
    }
    builder.append(TAB).append(TAB).append("handler.");
    if (advice.includeThis()) {
      builder.append("dupInvoke(owner, descriptor");
    } else {
      builder.append("dupParameters(descriptor");
    }
    builder.append(", StackDupMode.").append(mode).append(");").append(LINE_END);
  }

  private void writeOriginalMethodCall(
      @Nonnull final StringBuilder builder, @Nonnull final AdviceSpecification advice) {
    builder.append(TAB).append(TAB);
    if (advice.isInvokeDynamic()) {
      builder.append(
          "handler.invokeDynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);");
    } else {
      builder.append("handler.method(opcode, owner, name, descriptor, isInterface);");
    }
    builder.append(LINE_END);
  }

  private static void writeAdviceMethodCall(
      @Nonnull final StringBuilder builder, @Nonnull final AdviceSpecification advice) {
    final MethodType method = advice.getAdvice();
    if (advice instanceof AroundSpecification && advice.invokeDynamic) {
      builder
          .append(TAB)
          .append(TAB)
          .append("handler.invokeDynamic(name, descriptor, new Handle(Opcodes.H_INVOKESTATIC, \"")
          .append(method.getOwner().getInternalName())
          .append("\", \"")
          .append(method.getMethodName())
          .append("\", \"")
          .append(method.getMethodType().getDescriptor())
          .append("\", false), ")
          .append("bootstrapMethodArguments);")
          .append(LINE_END);
    } else {
      if (advice.isInvokeDynamic() && advice.findInvokeDynamicConstants() != null) {
        // we should add the boostrap method constants before the method call
        builder
            .append(TAB)
            .append(TAB)
            .append("handler.loadConstantArray(bootstrapMethodArguments);")
            .append(LINE_END);
      }
      builder
          .append(TAB)
          .append(TAB)
          .append("handler.method(Opcodes.INVOKESTATIC, \"")
          .append(method.getOwner().getInternalName())
          .append("\", \"")
          .append(method.getMethodName())
          .append("\", \"")
          .append(method.getMethodType().getDescriptor())
          .append("\", false);")
          .append(LINE_END);
      if (advice instanceof AfterSpecification && advice.isConstructor()) {
        // constructors make a DUP before the <init> that we have to discard
        builder
            .append(TAB)
            .append(TAB)
            .append("handler.instruction(Opcodes.POP);")
            .append(LINE_END);
      }
    }
  }

  private static String getPackageName(final Type type) {
    final String className = type.getClassName();
    final int index = type.getClassName().lastIndexOf('.');
    return index >= 0 ? className.substring(0, index) : null;
  }

  private static String getClassName(final Type type) {
    return getClassName(type, true);
  }

  private static String getClassName(final Type type, final boolean definition) {
    final String className = type.getClassName();
    final int index = type.getClassName().lastIndexOf('.');
    final String result = index >= 0 ? className.substring(index + 1) : className;
    return definition ? result : result.replace('$', '.');
  }

  private static void handleThrowable(
      @Nonnull final ValidationContext container, @Nonnull final Throwable t) {
    if (t instanceof HasErrors) {
      ((HasErrors) t).getErrors().forEach(container::addError);
    } else {
      container.addError(t, ErrorCode.UNCAUGHT_ERROR);
    }
  }

  private static Template createTemplate() {
    try {
      final Configuration cfg = new Configuration(Configuration.VERSION_2_3_30);
      cfg.setClassLoaderForTemplateLoading(Thread.currentThread().getContextClassLoader(), "csi");
      cfg.setDefaultEncoding("UTF-8");
      return cfg.getTemplate("advice.ftl");
    } catch (IOException e) {
      throw new IllegalArgumentException("Template not found", e);
    }
  }
}
