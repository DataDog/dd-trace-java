package otel.muzzle

import io.opentelemetry.javaagent.instrumentation.grpc.v1_6.GrpcInstrumentationModule
import io.opentelemetry.javaagent.tooling.muzzle.InstrumentationModuleMuzzle
import io.opentelemetry.javaagent.tooling.muzzle.references.ClassRef
import io.opentelemetry.javaagent.tooling.muzzle.references.FieldRef
import io.opentelemetry.javaagent.tooling.muzzle.references.Flag
import io.opentelemetry.javaagent.tooling.muzzle.references.MethodRef
import io.opentelemetry.javaagent.tooling.muzzle.references.Source
import org.objectweb.asm.ClassReader
import spock.lang.Specification

class MuzzleConverterTest extends Specification {
  def 'check muzzle converter reference extraction'(InstrumentationModuleMuzzle module) {
    setup:
    def expectedReferences = module.getMuzzleReferences().collectEntries {
      key, value -> [key , convert(value as ClassRef)]
    }
    def filename = "${module.class.simpleName}.class"
    def muzzleConverter = new MuzzleConverter(null, filename)

    when:
    def reader = new ClassReader(module.class.name)
    reader.accept(muzzleConverter, 0)
    def capturedReferences = [:] as Map<String, MuzzleReference>
    muzzleConverter.references.forEach {
      capturedReferences[it.className] = it
    }

    then:
    capturedReferences.size() == expectedReferences.size()
    expectedReferences.forEach { className, MuzzleReference expectedReference ->
      assertMuzzleReference(capturedReferences[className], expectedReference)
    }

    where:
    module << [new GrpcInstrumentationModule()]
  }

  // This might seems tedious to compare field be field rather than using built-in object comparison
  // But this is way more easy to debug and investigate any issue
  // The object to test can contains dozens of sub-structures and make the built-in comparison hard to read
  void assertMuzzleReference(MuzzleReference actual, MuzzleReference expected) {
    assert actual.sources.size() == expected.sources.size()
    assert actual.sources == expected.sources
    assert actual.flags == expected.flags
    assert actual.className == expected.className
    assert actual.superName == expected.superName
    assert actual.interfaces.size() == expected.interfaces.size()
    assert actual.interfaces == expected.interfaces
    assert actual.fields.size() == expected.fields.size()
    for (i in 0..<expected.fields.size()) {
      assertFieldReference(actual.fields[i], expected.fields[i])
    }
    assert actual.methods.size() == expected.methods.size()
    for (i in 0..<expected.methods.size()) {
      assertMethodReference(actual.methods[i], expected.methods[i])
    }
  }

  void assertFieldReference(MuzzleReference.Field actual, MuzzleReference.Field expected) {
    assert actual.sources.size() == expected.sources.size()
    assert actual.sources == expected.sources
    assert actual.flags == expected.flags
    assert actual.name == expected.name
    assert actual.fieldType == expected.fieldType
  }

  void assertMethodReference(MuzzleReference.Method actual, MuzzleReference.Method expected) {
    assert actual.sources.size() == expected.sources.size()
    assert actual.sources == expected.sources
    assert actual.flags == expected.flags
    assert actual.name == expected.name
    assert actual.methodType == expected.methodType
  }

  MuzzleReference convert(ClassRef classRef) {
    MuzzleReference reference = new MuzzleReference()
    reference.sources = classRef.sources.collect { convert(it) }
    reference.flags = convert(classRef.flags)
    reference.className = classRef.className
    reference.superName = classRef.superClassName
    reference.interfaces.addAll(classRef.interfaceNames)
    reference.fields = classRef.fields.collect { convert(it) }
    reference.methods = classRef.methods.collect { convert(it) }
    return reference
  }

  String convert(Source source) {
    return "$source.name:$source.line"
  }

  int convert(Set<Flag> flags) {
    def sum = flags.collect {
      MuzzleFlag.convertOtelFlag(it.class.superclass.simpleName, it.name())
    }.sum()
    return sum == null ? 0 : sum as int
  }

  MuzzleReference.Field convert(FieldRef fieldRef) {
    MuzzleReference.Field field = new MuzzleReference.Field()
    field.sources = fieldRef.sources.collect { convert(it) }
    field.flags = convert(fieldRef.flags)
    field.name = fieldRef.name
    field.fieldType = fieldRef.descriptor
    return field
  }

  MuzzleReference.Method convert(MethodRef methodRef) {
    MuzzleReference.Method method = new MuzzleReference.Method()
    method.sources = methodRef.sources.collect { convert(it) }
    method.flags = convert(methodRef.flags)
    method.name = methodRef.name
    method.methodType = methodRef.descriptor
    return method
  }
}
