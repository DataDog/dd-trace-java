package datadog.trace.plugin.csi.impl

import datadog.trace.plugin.csi.HasErrors
import datadog.trace.plugin.csi.ValidationContext
import datadog.trace.plugin.csi.util.MethodType
import groovy.transform.CompileDynamic
import org.objectweb.asm.Type
import spock.lang.Specification

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ParameterSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser
import static datadog.trace.plugin.csi.impl.CallSiteFactory.specificationBuilder
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER

@CompileDynamic
abstract class BaseCsiPluginTest extends Specification {

  protected static void assertNoErrors(final HasErrors hasErrors) {
    final errors = hasErrors.errors.collect {
      error ->
      "${error.message}: ${error.cause == null ? '-' : error.causeString}"
    }
    assert errors == []
  }

  protected static File fetchClass(final Class<?> clazz) {
    final folder = Paths.get(clazz.getResource('/').toURI()).resolve('../../')
    final fileSeparatorPattern = File.separator == "\\" ? "\\\\" : File.separator
    final classFile = clazz.getName().replaceAll('\\.', fileSeparatorPattern) + '.class'
    final groovy = folder.resolve('groovy/test').resolve(classFile)
    if (Files.exists(groovy)) {
      return groovy.toFile()
    }
    return folder.resolve('java/test').resolve(classFile).toFile()
  }

  protected static CallSiteSpecification buildClassSpecification(final Class<?> clazz) {
    final classFile = fetchClass(clazz)
    final spec = specificationBuilder().build(classFile).get()
    final pointcutParser = pointcutParser()
    spec.advices.each {
      it.parseSignature(pointcutParser)
    }
    return spec
  }

  protected ValidationContext mockValidationContext() {
    return Mock(ValidationContext) {
      mock ->
      mock.getContextProperty(TYPE_RESOLVER) >> typeResolver()
    }
  }

  protected static BeforeSpecification before(@DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = BeforeAdviceSpecificationBuilder) final Closure cl) {
    final spec = new BeforeAdviceSpecificationBuilder()
    final code = cl.rehydrate(spec, this, this)
    code.resolveStrategy = Closure.DELEGATE_ONLY
    code()
    return spec.build()
  }

  protected static AroundSpecification around(@DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = AroundAdviceSpecificationBuilder) final Closure cl) {
    final spec = new AroundAdviceSpecificationBuilder()
    final code = cl.rehydrate(spec, this, this)
    code.resolveStrategy = Closure.DELEGATE_ONLY
    code()
    return spec.build()
  }

  protected static AfterSpecification after(@DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = AfterAdviceSpecificationBuilder) final Closure cl) {
    final spec = new AfterAdviceSpecificationBuilder()
    final code = cl.rehydrate(spec, this, this)
    code.resolveStrategy = Closure.DELEGATE_ONLY
    code()
    return spec.build()
  }

  private static class BeforeAdviceSpecificationBuilder extends AdviceSpecificationBuilder {
    @Override
    protected AdviceSpecification build(final MethodType advice,
    final Map<Integer, ParameterSpecification> parameters,
    final String signature,
    final boolean invokeDynamic) {
      return new BeforeSpecification(advice, parameters, signature, invokeDynamic)
    }
  }

  private static class AroundAdviceSpecificationBuilder extends AdviceSpecificationBuilder {
    @Override
    protected AroundSpecification build(final MethodType advice,
    final Map<Integer, ParameterSpecification> parameters,
    final String signature,
    final boolean invokeDynamic) {
      return new AroundSpecification(advice, parameters, signature, invokeDynamic)
    }
  }

  private static class AfterAdviceSpecificationBuilder extends AdviceSpecificationBuilder {
    @Override
    protected AfterSpecification build(final MethodType advice,
    final Map<Integer, ParameterSpecification> parameters,
    final String signature,
    final boolean invokeDynamic) {
      return new AfterSpecification(advice, parameters, signature, invokeDynamic)
    }
  }

  private abstract static class AdviceSpecificationBuilder {
    protected MethodType advice
    protected Map<Integer, ParameterSpecification> parameters = [:]
    protected String signature
    protected boolean invokeDynamic

    void advice(@DelegatesTo(strategy = Closure.DELEGATE_ONLY, value = MethodTypeBuilder) final Closure body) {
      final spec = new MethodTypeBuilder()
      final code = body.rehydrate(spec, this, this)
      code.resolveStrategy = Closure.DELEGATE_ONLY
      code()
      advice = spec.build()
    }

    void parameters(final ParameterSpecification... parameters) {
      parameters.eachWithIndex {
        entry, int i -> this.parameters.put(i, entry)
      }
      parameters.grep {
        it instanceof ArgumentSpecification
      }
      .collect {
        it as ArgumentSpecification
      }
      .eachWithIndex{
        spec, int i -> spec.index = i
      }
    }

    void signature(final String signature) {
      this.signature = signature
    }

    void invokeDynamic(final boolean invokeDynamic) {
      this.invokeDynamic = invokeDynamic
    }

    <E extends AdviceSpecification> E build() {
      final result = build(advice, parameters, signature, invokeDynamic) as E
      result.parseSignature(pointcutParser())
      return result
    }


    protected abstract AdviceSpecification build(final MethodType advice,
    final Map<Integer, ParameterSpecification> parameters,
    final String signature,
    final boolean invokeDynamic)
  }

  private static class MethodTypeBuilder {
    protected Type owner
    protected String methodName
    protected Type methodType

    void owner(final Type value) {
      owner = value
    }

    void owner(final Class<?> value) {
      owner = Type.getType(value)
    }

    void method(final String value) {
      methodName = value
    }

    void descriptor(final Type value) {
      methodType = value
    }

    void descriptor(final Class<?> returnType, final Class<?>... args) {
      methodType = Type.getMethodType(Type.getType(returnType), args.collect {
        Type.getType(it)
      } as Type[])
    }

    void method(final Executable executable) {
      owner = Type.getType(executable.declaringClass)
      final args = executable.parameterTypes.collect {
        Type.getType(it)
      }.toArray(new Type[0]) as Type[]
      if (executable instanceof Constructor) {
        methodName = '<init>'
        methodType = Type.getMethodType(Type.VOID_TYPE, args)
      } else {
        final method = executable as Method
        methodName = method.name
        methodType = Type.getMethodType(Type.getType(method.getReturnType()), args)
      }
    }

    private MethodType build() {
      return new MethodType(owner, methodName, methodType)
    }
  }
}
