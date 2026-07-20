package datadog.trace.civisibility.codeowners;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CodeownersTest {

  private static final String GITHUB_SAMPLE = "ci/codeowners/CODEOWNERS_sample";
  private static final String GITLAB_SAMPLE = "ci/codeowners/CODEOWNERS_gitlab_sample";

  @ParameterizedTest(name = "[{0}] owners of {1} are {2}")
  @MethodSource("testCodeownersMatchingArguments")
  void testCodeownersMatching(String resource, String path, List<String> expectedOwners)
      throws IOException {
    Codeowners codeowners = parse(resource);
    assertEquals(expectedOwners, codeowners.getOwners(path));
  }

  static Stream<Arguments> testCodeownersMatchingArguments() {
    List<String> globalOwners = asList("@global-owner1", "@global-owner2");
    return Stream.of(
        // --- GitHub baseline: behavior must remain unchanged ---
        arguments(GITHUB_SAMPLE, "MyClass.java", globalOwners),
        arguments(GITHUB_SAMPLE, "folder/MyClass.java", globalOwners),
        arguments(GITHUB_SAMPLE, "script.js", singletonList("@js-owner")),
        arguments(GITHUB_SAMPLE, "inner/folder/script.js", singletonList("@js-owner")),
        arguments(GITHUB_SAMPLE, "scripts/script.js", asList("@doctocat", "@octocat")),
        arguments(GITHUB_SAMPLE, "scripts/inner/script.js", asList("@doctocat", "@octocat")),
        arguments(GITHUB_SAMPLE, "build/logs/current.log", singletonList("@doctocat")),
        arguments(GITHUB_SAMPLE, "build/logs/inner/current.log", singletonList("@doctocat")),
        arguments(GITHUB_SAMPLE, "module/build/logs/current.log", globalOwners),
        arguments(GITHUB_SAMPLE, "apps/app1.exe", singletonList("@octocat")),
        arguments(GITHUB_SAMPLE, "apps/inner/app2.exe", singletonList("@octocat")),
        arguments(GITHUB_SAMPLE, "module/apps/app3.exe", singletonList("@octocat")),
        arguments(GITHUB_SAMPLE, "docs/intro.doc", singletonList("@doctocat")),
        arguments(GITHUB_SAMPLE, "docs/important/doc.doc", singletonList("@doctocat")),
        arguments(GITHUB_SAMPLE, "applications/application", singletonList("@appowner")),
        arguments(GITHUB_SAMPLE, "applications/module/application", singletonList("@appowner")),
        arguments(GITHUB_SAMPLE, "applications/github/githubApplication", emptyList()),
        arguments(GITHUB_SAMPLE, "documentation/doc.md", singletonList("docs@example.com")),
        arguments(GITHUB_SAMPLE, "documentation/inner/doc.md", globalOwners),
        arguments(
            GITHUB_SAMPLE, "documentation/inner/doc.txt", singletonList("@octo-org/octocats")),
        // --- GitLab sections: a path's owners combine the winning match from every section,
        // including the unnamed section (the leading "* @global-owner") ---
        arguments(GITLAB_SAMPLE, "random/file.txt", singletonList("@global-owner")),
        // section default owners are inherited and combined with the global owner
        arguments(GITLAB_SAMPLE, "docs/guide.md", asList("@global-owner", "@docs-team")),
        arguments(GITLAB_SAMPLE, "README.md", asList("@global-owner", "@docs-team")),
        arguments(
            GITLAB_SAMPLE,
            "model/db/users.sql",
            asList("@global-owner", "@database-team", "@dba-lead")),
        // per-entry owners override the section default within that section
        arguments(
            GITLAB_SAMPLE,
            "config/db/setup.sql",
            asList("@global-owner", "@special-owner", "@config-team")),
        // optional section (^[...]) defaults are inherited just like required ones
        arguments(GITLAB_SAMPLE, "src/app.js", asList("@global-owner", "@frontend-team")),
        arguments(GITLAB_SAMPLE, "src/styles.css", asList("@global-owner", "@css-owner")),
        // a section without default owners keeps each entry's own owners
        arguments(GITLAB_SAMPLE, "scripts/deploy.sh", asList("@global-owner", "@scripts-team")),
        // the required-approvals count in the header is ignored for ownership
        arguments(GITLAB_SAMPLE, "secrets/key.txt", asList("@global-owner", "@security-team")),
        // a header immediately followed by a comment is a valid header with no default owners, so
        // this entry does not inherit the previous section's owners (only the global owner applies)
        arguments(GITLAB_SAMPLE, "generated/report.txt", singletonList("@global-owner")),
        // duplicate section names (case-insensitive) are combined into one section: a path matching
        // entries in both blocks resolves to the last matching entry's owners, not both
        arguments(GITLAB_SAMPLE, "service/util.go", asList("@global-owner", "@backend-team-a")),
        arguments(
            GITLAB_SAMPLE, "service/api/handler.go", asList("@global-owner", "@backend-team-b")));
  }

  private static Codeowners parse(String resource) throws IOException {
    try (InputStream stream = CodeownersTest.class.getClassLoader().getResourceAsStream(resource);
        Reader reader = new InputStreamReader(stream, UTF_8)) {
      return CodeownersImpl.parse(reader);
    }
  }
}
