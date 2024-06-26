package datadog.trace.civisibility.codeowners

import com.google.common.base.Charsets
import spock.lang.Specification

class CodeownersTest extends Specification {

  def "test codeowners matching: #path"() {
    setup:
    def codeowners = new InputStreamReader(CodeownersTest.getClassLoader().getResourceAsStream("ci/codeowners/CODEOWNERS_sample"), Charsets.UTF_8).withCloseable { reader ->
      CodeownersImpl.parse(reader)
    }

    when:
    def owners = codeowners.getOwners(path)

    then:
    owners == expectedOwners

    where:
    path                                    | expectedOwners
    "MyClass.java"                          | ["@global-owner1", "@global-owner2"]
    "folder/MyClass.java"                   | ["@global-owner1", "@global-owner2"]
    "script.js"                             | ["@js-owner"]
    "inner/folder/script.js"                | ["@js-owner"]
    "scripts/script.js"                     | ["@doctocat", "@octocat"]
    "scripts/inner/script.js"               | ["@doctocat", "@octocat"]
    "build/logs/current.log"                | ["@doctocat"]
    "build/logs/inner/current.log"          | ["@doctocat"]
    "module/build/logs/current.log"         | ["@global-owner1", "@global-owner2"]
    "apps/app1.exe"                         | ["@octocat"]
    "apps/inner/app2.exe"                   | ["@octocat"]
    "module/apps/app3.exe"                  | ["@octocat"]
    "docs/intro.doc"                        | ["@doctocat"]
    "docs/important/doc.doc"                | ["@doctocat"]
    "applications/application"              | ["@appowner"]
    "applications/module/application"       | ["@appowner"]
    "applications/github/githubApplication" | []
    "documentation/doc.md"                  | ["docs@example.com"]
    "documentation/inner/doc.md"            | ["@global-owner1", "@global-owner2"]
    "documentation/inner/doc.txt"           | ["@octo-org/octocats"]
  }
}
