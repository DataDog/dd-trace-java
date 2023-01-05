package datadog.trace.bootstrap.instrumentation.ci.codeowners

import com.google.common.base.Charsets
import spock.lang.Specification

class CodeownersTest extends Specification {

  def "test codeowners matching: #path"() {
    setup:
    def codeowners = new InputStreamReader(CodeownersTest.getClassLoader().getResourceAsStream("ci/codeowners/CODEOWNERS_sample"), Charsets.UTF_8).withCloseable { reader ->
      Codeowners.parse("/repo/root", reader)
    }

    when:
    def owners = codeowners.getOwners(path)

    then:
    owners == expectedOwners

    where:
    path                                               | expectedOwners
    "/outside/MyClass.java"                            | []
    "/repo/root/MyClass.java"                          | ["@global-owner1", "@global-owner2"]
    "/repo/root/folder/MyClass.java"                   | ["@global-owner1", "@global-owner2"]
    "/repo/root/script.js"                             | ["@js-owner"]
    "/repo/root/inner/folder/script.js"                | ["@js-owner"]
    "/repo/root/scripts/script.js"                     | ["@doctocat", "@octocat"]
    "/repo/root/scripts/inner/script.js"               | ["@doctocat", "@octocat"]
    "/repo/root/build/logs/current.log"                | ["@doctocat"]
    "/repo/root/build/logs/inner/current.log"          | ["@doctocat"]
    "/repo/root/module/build/logs/current.log"         | ["@global-owner1", "@global-owner2"]
    "/repo/root/apps/app1.exe"                         | ["@octocat"]
    "/repo/root/apps/inner/app2.exe"                   | ["@octocat"]
    "/repo/root/module/apps/app3.exe"                  | ["@octocat"]
    "/repo/root/docs/intro.doc"                        | ["@doctocat"]
    "/repo/root/docs/important/doc.doc"                | ["@doctocat"]
    "/repo/root/applications/application"              | ["@appowner"]
    "/repo/root/applications/module/application"       | ["@appowner"]
    "/repo/root/applications/github/githubApplication" | []
    "/repo/root/documentation/doc.md"                  | ["docs@example.com"]
    "/repo/root/documentation/inner/doc.md"            | ["@global-owner1", "@global-owner2"]
    "/repo/root/documentation/inner/doc.txt"           | ["@octo-org/octocats"]
  }
}
