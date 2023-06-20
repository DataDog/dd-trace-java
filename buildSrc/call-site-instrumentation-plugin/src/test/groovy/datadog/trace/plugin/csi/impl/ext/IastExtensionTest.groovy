package datadog.trace.plugin.csi.impl.ext

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import datadog.trace.agent.tooling.csi.CallSiteAdvice
import datadog.trace.agent.tooling.csi.CallSites
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
    typeName                           | expected
    CallSites.name                     | false
    IastExtension.IAST_CALL_SITES_FQCN | true
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

    then: 'the call site provider is modified with telemetry'
    final callSiteType = parse(callSite.file)
  }

  private static AdviceGenerator buildAdviceGenerator(final File targetFolder) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser())
  }

  private static Path getCallSiteSrcFolder() {
    final file = Thread.currentThread().contextClassLoader.getResource('')
    return Paths.get(file.toURI()).resolve('../../../../src/test/java')
  }

  private static ClassOrInterfaceDeclaration parse(final File path) {
    final parsedAdvice = new JavaParser().parse(path).getResult().get()
    return parsedAdvice.primaryType.get().asClassOrInterfaceDeclaration()
  }
}
