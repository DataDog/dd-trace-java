package datadog.trace.agent.tooling.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.BEFORE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.named;

import datadog.instrument.classinject.ClassInjector;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ServiceLoader;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

/** Shared helpers for call site instrumentation tests. */
abstract class BaseCallSiteTest extends DDJavaSpecification {

  private static StrictnessPolicy previousStrictnessPolicy;

  @BeforeAll
  static void setupCallSites() {
    previousStrictnessPolicy = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
    // install() reuses an already installed agent, and installs one when only JUnit tests run
    ClassInjector.enableClassInjection(ByteBuddyAgent.install());
  }

  @AfterAll
  static void cleanupCallSites() {
    ConfigHelper.get().setConfigInversionStrict(previousStrictnessPolicy);
  }

  protected static CallSites mockCallSites(
      CallSiteAdvice advice, Pointcut target, String... helpers) {
    return mockCallSites(BEFORE, advice, target, helpers);
  }

  protected static CallSites mockCallSites(
      byte type, CallSiteAdvice advice, Pointcut target, String... helpers) {
    return container -> {
      container.addHelpers(helpers);
      container.addAdvice(type, target.type, target.method, target.descriptor, advice);
    };
  }

  /**
   * Builds real {@link Advices} from the call sites with the no-op introspector, so no constant
   * pool introspection happens.
   */
  protected static Advices buildAdvices(Iterable<CallSites> callSites) {
    return Advices.fromCallSites(
        callSites, Advices.AdviceIntrospector.NoOpAdviceInstrospector.INSTANCE);
  }

  protected static Pointcut stringConcatPointcut() throws NoSuchMethodException {
    return buildPointcut(String.class.getDeclaredMethod("concat", String.class));
  }

  protected static Pointcut messageDigestGetInstancePointcut() throws NoSuchMethodException {
    return buildPointcut(MessageDigest.class.getDeclaredMethod("getInstance", String.class));
  }

  protected static Pointcut stringBuilderSetLengthPointcut() throws NoSuchMethodException {
    return buildPointcut(StringBuilder.class.getDeclaredMethod("setLength", int.class));
  }

  protected static Pointcut stringBuilderInsertPointcut() throws NoSuchMethodException {
    return buildPointcut(
        StringBuilder.class.getDeclaredMethod(
            "insert", int.class, char[].class, int.class, int.class));
  }

  protected static Pointcut stringReaderPointcut() throws NoSuchMethodException {
    return buildPointcut(StringReader.class.getDeclaredConstructor(String.class));
  }

  protected static Pointcut stringConcatFactoryPointcut() {
    return buildPointcut(
        "java/lang/invoke/StringConcatFactory",
        "makeConcatWithConstants",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");
  }

  protected static Pointcut buildPointcut(Method executable) {
    return buildPointcut(
        Type.getType(executable.getDeclaringClass()).getInternalName(),
        executable.getName(),
        Type.getType(executable).getDescriptor());
  }

  protected static Pointcut buildPointcut(Constructor<?> executable) {
    return buildPointcut(
        Type.getType(executable.getDeclaringClass()).getInternalName(),
        "<init>",
        Type.getType(executable).getDescriptor());
  }

  protected static Pointcut buildPointcut(String type, String method, String descriptor) {
    return new Pointcut(type, method, descriptor);
  }

  protected static CallSiteInstrumentation buildInstrumentation(Iterable<CallSites> callSites) {
    return buildInstrumentation(callSites, any());
  }

  protected static CallSiteInstrumentation buildInstrumentation(
      Iterable<CallSites> callSites, ElementMatcher<TypeDescription> callerType) {
    return new CallSiteInstrumentation("csi") {
      @Override
      public ElementMatcher<TypeDescription> callerType() {
        return callerType;
      }

      @Override
      protected CallSiteSupplier callSites() {
        return () -> callSites;
      }
    };
  }

  protected static CallSiteInstrumentation buildInstrumentation(Class<?> spiClass) {
    return buildInstrumentation(spiClass, any());
  }

  @SuppressWarnings("unchecked")
  protected static CallSiteInstrumentation buildInstrumentation(
      Class<?> spiClass, ElementMatcher<TypeDescription> callerType) {
    return new CallSiteInstrumentation("csi") {
      @Override
      public ElementMatcher<TypeDescription> callerType() {
        return callerType;
      }

      @Override
      protected CallSiteSupplier callSites() {
        return () -> {
          ClassLoader targetClassLoader = CallSiteInstrumentation.class.getClassLoader();
          return (Iterable<CallSites>) ServiceLoader.load(spiClass, targetClassLoader);
        };
      }
    };
  }

  protected static Type renameType(Type sourceType, String suffix) {
    return Type.getType(sourceType.getDescriptor().replace(";", suffix + ";"));
  }

  protected static Object loadType(Type type, byte[] data) throws ReflectiveOperationException {
    return loadClass(type, data).getConstructor().newInstance();
  }

  protected static Class<?> loadClass(Type type, byte[] data) throws ClassNotFoundException {
    return loadClass(type, data, Thread.currentThread().getContextClassLoader());
  }

  protected static Class<?> loadClass(Type type, byte[] data, ClassLoader loader)
      throws ClassNotFoundException {
    ClassLoader classLoader =
        new ByteArrayClassLoader(loader, singletonMap(type.getClassName(), data));
    return classLoader.loadClass(type.getClassName());
  }

  protected static byte[] transformType(Type source, Type target, CallSiteTransformer transformer)
      throws IOException, IllegalClassFormatException {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    byte[] classContent;
    try (InputStream classStream =
        loader.getResourceAsStream(source.getInternalName() + ".class")) {
      classContent = IOUtils.toByteArray(classStream);
    }
    return transformType(source, classContent, target, transformer, loader);
  }

  protected static byte[] transformType(
      Type source, byte[] classContent, Type target, CallSiteTransformer transformer)
      throws IllegalClassFormatException {
    return transformType(
        source, classContent, target, transformer, Thread.currentThread().getContextClassLoader());
  }

  protected static byte[] transformType(
      Type source,
      byte[] classContent,
      Type target,
      CallSiteTransformer transformer,
      ClassLoader loader)
      throws IllegalClassFormatException {
    ClassFileTransformer classFileTransformer =
        new AgentBuilder.Default()
            .type(named(source.getClassName()))
            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                transformer
                    .transform(builder, typeDescription, classLoader, module, protectionDomain)
                    .name(target.getClassName()))
            .makeRaw();
    return classFileTransformer.transform(loader, source.getClassName(), null, null, classContent);
  }

  protected static final class Pointcut {
    final String type;
    final String method;
    final String descriptor;

    Pointcut(String type, String method, String descriptor) {
      this.type = type;
      this.method = method;
      this.descriptor = descriptor;
    }

    @Override
    public String toString() {
      return descriptor;
    }
  }

  /** Converts a pointcut name (e.g. {@code stringConcat}) to the matching {@link Pointcut}. */
  static final class PointcutConverter implements ArgumentConverter {
    @Override
    public Object convert(Object source, ParameterContext context)
        throws ArgumentConversionException {
      String name = String.valueOf(source);
      try {
        switch (name) {
          case "stringConcat":
            return stringConcatPointcut();
          case "stringConcatFactory":
            return stringConcatFactoryPointcut();
          case "messageDigestGetInstance":
            return messageDigestGetInstancePointcut();
          default:
            throw new ArgumentConversionException("Unknown pointcut: " + name);
        }
      } catch (NoSuchMethodException e) {
        throw new ArgumentConversionException("Cannot build pointcut: " + name, e);
      }
    }
  }
}
