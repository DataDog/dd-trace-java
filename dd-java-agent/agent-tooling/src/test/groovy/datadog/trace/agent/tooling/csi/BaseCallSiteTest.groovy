package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumenter
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.utility.JavaModule
import net.bytebuddy.utility.nullability.MaybeNull
import datadog.trace.agent.tooling.csi.CallSiteAdvice.HasHelpers
import datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.security.MessageDigest

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags.COMPUTE_MAX_STACK


import java.lang.reflect.Method
import java.security.ProtectionDomain

import static net.bytebuddy.matcher.ElementMatchers.any
import static net.bytebuddy.matcher.ElementMatchers.named

class BaseCallSiteTest extends DDSpecification {

  protected static final Logger LOG = LoggerFactory.getLogger(CallSiteTransformerInvokeDynamicTest)


  protected InvokeAdvice mockInvokeAdvice(final Pointcut target) {
    return Mock(InvokeAdvice) {
      pointcut() >> target
    }
  }

  protected InvokeDynamicAdvice mockInvokeDynamicAdvice(final Pointcut target) {
    return Mock(InvokeDynamicAdvice) {
      pointcut() >> target
    }
  }

  protected InvokeAdvice mockInvokeAdvice(final Pointcut target, final int flagsValue) {
    return Mock(InvokeAdviceWithFlags) {
      pointcut() >> target
      flags() >> flagsValue
    }
  }

  protected InvokeDynamicAdvice mockInvokeDynamicAdvice(final Pointcut target, final int flagsValue) {
    return Mock(InvokeDynamicAdviceWithFlags) {
      pointcut() >> target
      flags() >> flagsValue
    }
  }

  protected InvokeAdvice mockInvokeAdvice(final Pointcut target, final String... helpers) {
    return Mock(InvokeAdviceWithHelpers) {
      pointcut() >> target
      helperClassNames() >> helpers
    }
  }

  protected InvokeDynamicAdvice mockInvokeDynamicAdvice(final Pointcut target, final String... helpers) {
    return Mock(InvokeDynamicAdviceWithHelpers) {
      pointcut() >> target
      helperClassNames() >> helpers
    }
  }

  protected InvokeAdvice mockInvokeAdvice(final Pointcut target, final int flagsValue, final String... helpers) {
    return Mock(InvokeAdviceWithFlagsAndHelpers) {
      pointcut() >> target
      flags() >> flagsValue
      helperClassNames() >> helpers
    }
  }

  protected InvokeDynamicAdvice mockInvokeDynamicAdvice(final Pointcut target, final int flagsValue, final String... helpers) {
    return Mock(InvokeDynamicAdviceWithFlagsAndHelpers) {
      pointcut() >> target
      flags() >> flagsValue
      helperClassNames() >> helpers
    }
  }

  protected Advices mockAdvices(final Collection<CallSiteAdvice> advices) {
    final computedFlags = advices.inject(0) { result, advice ->
      return advice instanceof HasFlags ? (result | (advice as HasFlags).flags()) : result
    }
    final adviceFinder = { final String owner, final String name, final String desc ->
      return advices.find {
        final pointcut = it.pointcut()
        return pointcut.type() == owner && pointcut.method() == name && pointcut.descriptor() == desc
      }
    }
    return Mock(Advices) {
      isEmpty() >> advices.isEmpty()
      findAdvices(_ as TypeDescription, _ as ClassLoader) >> it
      findAdvice(_ as Pointcut) >> { params ->
        final pointcut = (params as Object[])[0] as Pointcut
        adviceFinder.call(pointcut.type(), pointcut.method(), pointcut.descriptor())
      }
      findAdvice(_ as Handle) >> { params ->
        final handle = (params as Object[])[0] as Handle
        adviceFinder.call(handle.getOwner(), handle.getName(), handle.getDesc())
      }
      findAdvice(_ as String, _ as String, _ as String) >> { params ->
        final Object[] args = params as Object[]
        adviceFinder.call(args[0] as String, args[1] as String, args[2] as String)
      }
      hasFlag(_ as int) >> { params -> (params as Object[])[0] as int & computedFlags}
      computeMaxStack() >> { COMPUTE_MAX_STACK & computedFlags}
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
    return new Pointcut() {
        @Override
        String type() {
          return type
        }

        @Override
        String method() {
          return method
        }

        @Override
        String descriptor() {
          return descriptor
        }
      }
  }

  protected static CallSiteInstrumenter buildInstrumenter(final Iterable<CallSiteAdvice> advices,
    final ElementMatcher<TypeDescription> callerType = any()) {
    return new CallSiteInstrumenter(advices, 'csi') {
        @Override
        ElementMatcher<TypeDescription> callerType() {
          return callerType
        }
      }
  }

  protected static CallSiteInstrumenter buildInstrumenter(final Class<?> spiClass,
    final ElementMatcher<TypeDescription> callerType = any()) {
    return new CallSiteInstrumenter(spiClass, 'csi') {
        @Override
        ElementMatcher<TypeDescription> callerType() {
          return callerType
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

  interface InvokeAdviceWithHelpers extends InvokeAdvice, HasHelpers {
  }

  interface InvokeDynamicAdviceWithHelpers extends InvokeDynamicAdvice, HasHelpers {
  }

  interface InvokeAdviceWithFlags extends InvokeAdvice, HasFlags {
  }

  interface InvokeDynamicAdviceWithFlags extends InvokeDynamicAdvice, HasFlags {
  }

  interface InvokeAdviceWithFlagsAndHelpers extends InvokeAdvice, HasFlags, HasHelpers {
  }

  interface InvokeDynamicAdviceWithFlagsAndHelpers extends InvokeDynamicAdvice, HasFlags, HasHelpers {
  }
}
