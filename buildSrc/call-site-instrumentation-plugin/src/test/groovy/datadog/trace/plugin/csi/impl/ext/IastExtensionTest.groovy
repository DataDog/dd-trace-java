package datadog.trace.plugin.csi.impl.ext

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import datadog.trace.agent.tooling.csi.CallSiteAdvice
import datadog.trace.plugin.csi.AdviceGenerator
import datadog.trace.plugin.csi.PluginApplication.Configuration
import datadog.trace.plugin.csi.impl.AdviceGeneratorImpl
import datadog.trace.plugin.csi.impl.BaseCsiPluginTest
import datadog.trace.plugin.csi.impl.CallSiteSpecification
import datadog.trace.plugin.csi.impl.ext.tests.IastExtensionCallSite
import groovy.transform.CompileDynamic
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static datadog.trace.plugin.csi.impl.CallSiteFactory.pointcutParser
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType

@CompileDynamic
class IastExtensionTest extends BaseCsiPluginTest {

  @TempDir
  private File buildDir
  private Path targetFolder
  private Path projectFolder
  private Path srcFolder

  void setup() {
    targetFolder = buildDir.toPath().resolve('target')
    Files.createDirectories(targetFolder)
    projectFolder = buildDir.toPath().resolve('project')
    Files.createDirectories(projectFolder)
    srcFolder = projectFolder.resolve('src/main/java')
    Files.createDirectories(srcFolder)
  }

  void 'test that extension only applies to iast advices'() {
    setup:
    final type = classNameToType(typeName)
    final callSite = Mock(CallSiteSpecification) {
      getSpi() >> type
    }
    final extension = new IastExtension()

    when:
    final applies = extension.appliesTo(callSite)

    then:
    applies == expected

    where:
    typeName                       | expected
    CallSiteAdvice.name            | false
    IastExtension.IAST_ADVICE_FQCN | true
  }

  void 'test that extension generates a call site with telemetry'() {
    setup:
    final config = Mock(Configuration) {
      getTargetFolder() >> targetFolder
      getSrcFolder() >> getCallSiteSrcFolder()
      getClassPath() >> []
    }
    final spec = buildClassSpecification(IastExtensionCallSite)
    final generator = buildAdviceGenerator(buildDir)
    final callSite = generator.generate(spec)
    if (!callSite.success) {
      throw new IllegalArgumentException("Error with call site ${IastExtensionCallSite}")
    }
    final extension = new IastExtension()

    when:
    extension.apply(config, callSite)

    then:
    final String callSiteWithTelemetryClass = IastExtensionCallSite.name + IastExtension.WITH_TELEMETRY_SUFFIX
    final fileSeparatorPattern = File.separator == "\\" ? "\\\\" : File.separator
    final resultFile = targetFolder.resolve("${callSiteWithTelemetryClass.replaceAll('\\.', fileSeparatorPattern)}.java")
    assert Files.exists(resultFile)
    final callSiteType = parse(resultFile.toFile())
    validateMethod(callSiteType, 'afterGetHeader') { List<String> statements ->
      assert statements[0] == 'IastTelemetryCollector.add(IastMetric.EXECUTED_SOURCE, 1, SourceTypes.REQUEST_HEADER_VALUE);'
    }

    callSite.getAdvices().each { result ->
      final type = callSite.specification.clazz
      final adviceType = parse(result.file)
      final interfaces = getImplementedTypes(adviceType)
      assert interfaces.contains('HasTelemetry')
      validateField(adviceType, 'telemetry') { String field ->
        assert field == 'private boolean telemetry = false;'
      }
      validateField(adviceType, 'callSite') { String field ->
        assert field == "private String callSite = \"${type.internalName}\";"
      }
      validateField(adviceType, 'helperClassNames') { String field ->
        assert field == "private String[] helperClassNames = new String[] { \"${type.className}\" };"
      }
      validateMethod(adviceType, 'apply') { List<String> statements ->
        assert statements == [
          'if (this.telemetry) {' + System.lineSeparator() +
          '    IastTelemetryCollector.add(IastMetric.INSTRUMENTED_SOURCE, 1, SourceTypes.REQUEST_HEADER_VALUE);' + System.lineSeparator() +
          '}',
          'handler.dupInvoke(owner, descriptor, StackDupMode.COPY);',
          'handler.method(opcode, owner, name, descriptor, isInterface);',
          'handler.method(Opcodes.INVOKESTATIC, this.callSite, "afterGetHeader", "(Ljavax/servlet/http/HttpServletRequest;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);'
        ]
      }
      validateMethod(adviceType, 'helperClassNames') { List<String> statements ->
        assert statements == ['return this.helperClassNames;']
      }
      validateMethod(adviceType, 'kind') { List<String> statements ->
        assert statements == ['return IastAdvice.Kind.SOURCE;']
      }
      validateMethod(adviceType, 'enableTelemetry') { List<String> statements ->
        assert statements == [
          'this.telemetry = true;',
          'if (enableRuntime) {' + System.lineSeparator() +
          '    this.callSite = "' + type.internalName + 'WithTelemetry";' + System.lineSeparator() +
          '    this.helperClassNames = new String[] { "' + type.className + 'WithTelemetry" };' + System.lineSeparator() +
          '}',
        ]
      }
    }
  }

  private static void validateMethod(final TypeDeclaration<?> type, final String name, final Closure<?> validator) {
    validator.call(getMethod(type, name).body.get().statements*.toString())
  }

  private static void validateField(final TypeDeclaration<?> type, final String name, final Closure<?> validator) {
    validator.call(type.getFieldByName(name).get().toString())
  }

  private static MethodDeclaration getMethod(final TypeDeclaration<?> type, final String name) {
    return type.getMethods().find { it.nameAsString == name }
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static Path getCallSiteSrcFolder() {
    final file = Thread.currentThread().contextClassLoader.getResource('')
    return Paths.get(file.toURI()).resolve('../../../../src/test/java')
  }

  private static List<String> getImplementedTypes(final TypeDeclaration<?> type) {
    return type.asClassOrInterfaceDeclaration().implementedTypes*.nameAsString
  }

  private static TypeDeclaration<?> parse(final File path) {
    final parsedAdvice = new JavaParser().parse(path).getResult().get()
    return parsedAdvice.primaryType.get()
  }
}
