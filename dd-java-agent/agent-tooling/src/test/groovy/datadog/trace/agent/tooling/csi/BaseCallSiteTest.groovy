package datadog.trace.agent.tooling.csi

import datadog.trace.agent.tooling.bytebuddy.csi.Advices
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteInstrumenter
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.utility.JavaModule
import net.bytebuddy.utility.nullability.MaybeNull

import java.lang.reflect.Method

import static net.bytebuddy.matcher.ElementMatchers.any
import static net.bytebuddy.matcher.ElementMatchers.named

class BaseCallSiteTest extends DDSpecification {

  protected CallSiteAdvice mockAdvice(final Pointcut target) {
    return Mock(CallSiteAdvice) {
      pointcut() >> target
    }
  }

  protected CallSiteAdvice mockAdvice(final Pointcut target, final String... helpers) {
    return Mock(CallSiteAdviceWithHelpers) {
      pointcut() >> target
      helperClassNames() >> helpers
    }
  }

  protected Advices mockAdvices(final Collection<CallSiteAdvice> advices) {
    return Mock(Advices) {
      isEmpty() >> advices.isEmpty()
      findAdvices(_ as TypeDescription, _ as ClassLoader) >> it
      findAdvice(_ as String, _ as String, _ as String) >> { params ->
        final Object[] args = params as Object[]
        advices.find {
          final pointcut = it.pointcut()
          return pointcut.type() == args[0] as String &&
            pointcut.method() == args[1] as String &&
            pointcut.descriptor() == args[2] as String
        }
      }
    }
  }

  protected static Pointcut buildPointcut(final Method executable) {
    return new Pointcut() {
        @Override
        String type() {
          return Type.getType(executable.getDeclaringClass()).internalName
        }

        @Override
        String method() {
          return executable.name
        }

        @Override
        String descriptor() {
          return Type.getType(executable).descriptor
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
          final @MaybeNull JavaModule module) {
          return transformer
            .transform(builder, typeDescription, classLoader, module)
            .name(target.className)
        }
      })
      .makeRaw()
    return classFileTransformer.transform(loader, source.className, null, null, classContent)
  }

  interface CallSiteAdviceWithHelpers extends CallSiteAdvice, CallSiteAdvice.HasHelpers {
  }
}
