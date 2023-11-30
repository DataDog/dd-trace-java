package datadog.trace.civisibility.source.index

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import datadog.trace.api.Config
import spock.lang.Specification

class ConventionBasedResourceResolverTest extends Specification {

  def "test resource root resolution: #path"() {
    setup:
    def fileSystem = Jimfs.newFileSystem(Configuration.unix())
    def resourcePath = fileSystem.getPath(path)

    when:
    def resourceResolver = new ConventionBasedResourceResolver(fileSystem, Config.get().ciVisibilityResourceFolderNames)
    def resourceRoot = resourceResolver.getResourceRoot(resourcePath)

    then:
    resourceRoot == fileSystem.getPath(expectedResourceRoot)

    where:
    path                                               | expectedResourceRoot
    "/root/src/test/groovy/features/MyFeature.feature" | "/root/src/test/groovy"
    "/root/src/main/java/junit.properties"             | "/root/src/main/java"
    "/root/src/main/resources/my/package/fixture.json" | "/root/src/main/resources"
  }
}
