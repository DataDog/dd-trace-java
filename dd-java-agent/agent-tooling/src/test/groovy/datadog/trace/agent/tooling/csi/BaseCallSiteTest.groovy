package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumentation
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteSupplier
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.utility.JavaModule
import net.bytebuddy.utility.nullability.MaybeNull
import java.security.MessageDigest



import java.lang.reflect.Method
import java.security.ProtectionDomain

import static net.bytebuddy.matcher.ElementMatchers.any
import static net.bytebuddy.matcher.ElementMatchers.named

@CompileDynamic
class BaseCallSiteTest extends DDSpecification {

  protected CallSites mockCallSites(final CallSiteAdvice advice, final Pointcut target, final String... helpers) {
    return Mock(CallSites) {
      accept(_ as CallSites.Container) >> {
        final container = it[0] as CallSites.Container
        container.addHelpers(helpers)
        container.addAdvice(target.type, target.method, target.descriptor, advice)
      }
    }
  }

  protected Advices mockAdvices(final Collection<CallSites> callSites) {
    final advices = [:] as Map<String, Map<String, Map<String, CallSiteAdvice>>>
    final helpers = [] as Set<String>
    final container = Mock(CallSites.Container) {
      addAdvice(_ as String, _ as String, _ as String, _ as CallSiteAdvice) >> {
        advices.computeIfAbsent(it[0] as String, t -> [:])
        .computeIfAbsent(it[1] as String, m -> [:])
        .put(it[2] as String, it[3] as CallSiteAdvice)
      }
      addHelpers(_ as String[]) >> {
        Collections.addAll(helpers, it[0] as String[])
      }
    }
    callSites.each {
      it.accept(container)
    }
    final adviceFinder = {
      final String owner, final String name, final String desc ->
      return advices.get(owner)?.get(name)?.get(desc)
    }
    return Mock(Advices) {
      isEmpty() >> advices.isEmpty()
      findAdvices(_ as DynamicType.Builder, _ as TypeDescription, _ as ClassLoader) >> it
      findAdvice(_ as Handle) >> {
        params ->
        final handle = (params as Object[])[0] as Handle
        adviceFinder.call(handle.getOwner(), handle.getName(), handle.getDesc())
      }
      findAdvice(_ as String, _ as String, _ as String) >> {
        params ->
        final Object[] args = params as Object[]
        adviceFinder.call(args[0] as String, args[1] as String, args[2] as String)
      }
      getHelpers() >> {
        helpers as String[]
      }
    }
  }

  protected static Pointcut stringConcatPointcut() {
    return buildPointcut(String.getDeclaredMethod('concat', String))
  }

  protected static Pointcut messageDigestGetInstancePointcut() {
    return buildPointcut(MessageDigest.getDeclaredMethod('getInstance', String))
  }

  protected static Pointcut stringBuilderInsertPointcut() {
    return buildPointcut(StringBuilder.getDeclaredMethod('insert', int, char[], int, int))
  }

  protected static Pointcut stringConcatFactoryPointcut() {
    return buildPointcut(
    'java/lang/invoke/StringConcatFactory',
    'makeConcatWithConstants',
    '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;')
  }

  protected static Pointcut buildPointcut(final Method executable) {
    return buildPointcut(Type.getType(executable.getDeclaringClass()).internalName, executable.name, Type.getType(executable).descriptor)
  }

  protected static Pointcut buildPointcut(final String type, final String method, final String descriptor) {
    return new Pointcut(type: type, method: method, descriptor: descriptor)
  }

  protected static CallSiteInstrumentation buildInstrumentation(final Iterable<CallSites> advices,
  final ElementMatcher<TypeDescription> callerType = any()) {
    return new CallSiteInstrumentation('csi') {
      @Override
      ElementMatcher<TypeDescription> callerType() {
        return callerType
      }

      @Override
      protected CallSiteSupplier callSites() {
        return new CallSiteSupplier() {
          @Override
          Iterable<CallSites> get() {
            return advices
          }
        }
      }
    }
  }

  protected static CallSiteInstrumentation buildInstrumentation(final Class<?> spiClass,
  final ElementMatcher<TypeDescription> callerType = any()) {
    return new CallSiteInstrumentation('csi') {
      @Override
      ElementMatcher<TypeDescription> callerType() {
        return callerType
      }

      @Override
      protected CallSiteSupplier callSites() {
        return new CallSiteSupplier() {
          @Override
          Iterable<CallSites> get() {
            final targetClassLoader = CallSiteInstrumentation.classLoader
            return (ServiceLoader<CallSites>) ServiceLoader.load(spiClass, targetClassLoader)
          }
        }
      }
    }
  }

  protected static Type renameType(final Type sourceType, final String suffix) {
    return Type.getType(sourceType.descriptor.replace(';', "${suffix};"))
  }

  protected static Object loadType(final Type type,
  final byte[] data,
  final ClassLoader loader = Thread.currentThread().contextClassLoader) {
    final classLoader = new ByteArrayClassLoader(loader, [(type.className): data])
    final Class<?> clazz = classLoader.loadClass(type.className)
    return clazz.getConstructor().newInstance()
  }

  protected static byte[] transformType(final Type source,
  final Type target,
  final CallSiteTransformer transformer,
  final ClassLoader loader = Thread.currentThread().contextClassLoader) {
    final classContent = loader.getResourceAsStream("${source.getInternalName()}.class").bytes
    final classFileTransformer = new AgentBuilder.Default()
    .type(named(source.className))
    .transform(new AgentBuilder.Transformer() {
      @Override
      DynamicType.Builder<?> transform(final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final @MaybeNull ClassLoader classLoader,
      final @MaybeNull JavaModule module,
      final ProtectionDomain pd) {
        return transformer
        .transform(builder, typeDescription, classLoader, module, pd)
        .name(target.className)
      }
    })
    .makeRaw()
    return classFileTransformer.transform(loader, source.className, null, null, classContent)
  }

  protected static class Pointcut {
    String type
    String method
    String descriptor
  }
}
