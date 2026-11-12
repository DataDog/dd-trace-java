package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.PROCESS_TAGS_MAPPING;
import static datadog.trace.api.env.FixedCapturedEnvironment.processInfo;
import static datadog.trace.api.env.FixedCapturedEnvironment.useFixedProcessInfo;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.env.CapturedEnvironment.ProcessInfo;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import datadog.trace.util.TraceUtils;
import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class ProcessTagsForkedTest {

  private ProcessInfo originalProcessInfo;
  private Function<String, String> originalEnvGetter;

  @BeforeEach
  void setup() {
    originalProcessInfo = CapturedEnvironment.get().getProcessInfo();
    originalEnvGetter = ProcessTags.envGetter;
    ProcessTags.reset();
  }

  @AfterEach
  void cleanup() {
    useFixedProcessInfo(originalProcessInfo);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shouldLoadDefaultTagsArguments")
  void shouldLoadDefaultTags(String description, File jar, String mainClass, String expected) {
    useFixedProcessInfo(processInfo(mainClass, jar));
    ProcessTags.reset();
    UTF8BytesString tags = ProcessTags.getTagsForSerialization();

    assertMatches(expected, tags);
  }

  static Stream<Arguments> shouldLoadDefaultTagsArguments() {
    return Stream.of(
        arguments(
            "jar in base dir",
            Paths.get("my test", "my.jar").toFile(),
            null,
            "entrypoint.basedir:my_test,entrypoint.name:my,entrypoint.type:jar,entrypoint.workdir:[^,]+"),
        arguments(
            "jar without base dir",
            Paths.get("my.jar").toFile(),
            null,
            "entrypoint.name:my,entrypoint.type:jar,entrypoint.workdir:[^,]+"),
        arguments(
            "main class",
            null,
            "com.test.Main",
            "entrypoint.name:com.test.main,entrypoint.type:class,entrypoint.workdir:[^,]+"),
        arguments("no jar nor main class", null, null, "entrypoint.workdir:[^,]+"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shouldAddProcessTagsForServiceNameSetByUserArguments")
  void shouldAddProcessTagsForServiceNameSetByUser(
      String description, String userServiceName, String expected) {
    if (userServiceName != null) {
      injectSysConfig(DDTags.SERVICE, userServiceName);
    }
    ProcessTags.reset();
    UTF8BytesString tags = ProcessTags.getTagsForSerialization();

    assertMatches(expected, tags);
  }

  static Stream<Arguments> shouldAddProcessTagsForServiceNameSetByUserArguments() {
    return Stream.of(
        arguments(
            "auto service name",
            null,
            "svc.auto:" + TraceUtils.normalizeServiceName(Config.get().getServiceName())),
        arguments("user service name", "custom", "svc.user:true"));
  }

  @TableTest({
    "scenario           | jbossHome           | mode              | serverName | expected                                                                                                                                                                                 ",
    "standalone mode    | /opt/jboss/myserver | '[Standalone]'    | standalone | 'entrypoint.basedir:somewhere,entrypoint.name:jboss-modules,entrypoint.type:jar,entrypoint.workdir:.+,jboss.home:myserver,jboss.mode:standalone,server.name:standalone,server.type:jboss'",
    "domain mode        | /opt/jboss/myserver | '[server1:12345]' | server1    | 'entrypoint.basedir:somewhere,entrypoint.name:jboss-modules,entrypoint.type:jar,entrypoint.workdir:.+,jboss.home:myserver,jboss.mode:domain,server.name:server1,server.type:jboss'       ",
    // don't expect jboss tags since home is missing
    "missing jboss home |                     | '[Standalone]'    | standalone | 'entrypoint.basedir:somewhere,entrypoint.name:jboss-modules,entrypoint.type:jar,entrypoint.workdir:[^,]+'                                                                                "
  })
  void shouldLoadDefaultTagsJboss(
      String jbossHome, String mode, String serverName, String expected) {
    if (jbossHome != null) {
      System.setProperty("jboss.home.dir", jbossHome);
    }
    System.setProperty(mode, ""); // i.e. -D[Standalone]
    System.setProperty("jboss.server.name", serverName);
    try {
      useFixedProcessInfo(processInfo(null, new File("/somewhere/jboss-modules.jar")));
      ProcessTags.reset();
      UTF8BytesString tags = ProcessTags.getTagsForSerialization();

      assertMatches(expected, tags);
    } finally {
      System.clearProperty(mode);
      System.clearProperty("jboss.home.dir");
      System.clearProperty("jboss.server.name");
    }
  }

  @TableTest({
    "scenario     | cellName | serverName | expected                                                           ",
    "with cell    | cell1    | server1    | 'cluster.name:cell1,.+,server.name:server1,server.type:websphere.*'",
    "without cell |          | server1    | '^((?!cluster.name|server.name|server.type).)*$'                   "
  })
  void shouldLoadWebsphereTags(String cellName, String serverName, String expected) {
    ProcessTags.envGetter =
        key -> {
          switch (key) {
            case "WAS_CELL":
              return cellName;
            case "SERVER_NAME":
              return serverName;
            default:
              return null;
          }
        };
    try {
      ProcessTags.reset();

      UTF8BytesString tags = ProcessTags.getTagsForSerialization();

      assertMatches(expected, tags);
    } finally {
      ProcessTags.envGetter = originalEnvGetter;
      ProcessTags.reset();
    }
  }

  @Test
  @WithConfig(key = EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, value = "false")
  void canDisableProcessTags() {
    ProcessTags.reset();
    UTF8BytesString processTags = ProcessTags.getTagsForSerialization();

    assertFalse(ProcessTags.isEnabled());
    assertNull(processTags);

    ProcessTags.addTag("test", "value");

    assertNull(ProcessTags.getTagsForSerialization());
    assertNull(ProcessTags.getTagsAsStringList());
    assertNull(ProcessTags.getTagsAsUTF8ByteStringList());
  }

  @Test
  void shouldLazilyRecalculateWhenATagIsAdded() {
    ProcessTags.reset();

    UTF8BytesString processTags = ProcessTags.getTagsForSerialization();
    List<String> tagsAsList = ProcessTags.getTagsAsStringList();
    List<UTF8BytesString> tagsAsUtf8List = ProcessTags.getTagsAsUTF8ByteStringList();

    assertTrue(ProcessTags.isEnabled());
    assertNotNull(processTags);
    assertNotNull(tagsAsList);
    assertTrue(tagsAsList.size() > 0);
    assertNotNull(tagsAsUtf8List);
    assertEquals(tagsAsList.size(), tagsAsUtf8List.size());

    // add it as first pos since 0 < any other a-z
    ProcessTags.addTag("0test", "value");

    assertEquals("0test:value," + processTags, ProcessTags.getTagsForSerialization().toString());
    int size = ProcessTags.getTagsAsStringList().size();
    assertEquals(tagsAsList.size() + 1, size);
    assertEquals(size, ProcessTags.getTagsAsUTF8ByteStringList().size());
    assertEquals("0test:value", ProcessTags.getTagsAsStringList().get(0));
    assertEquals("0test:value", ProcessTags.getTagsAsUTF8ByteStringList().get(0).toString());
  }

  @Test
  @WithConfig(key = "my.property", value = "my.value", addPrefix = false)
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{prop}my.property:some_key")
  void shouldResolveProcessTagFromSystemPropertyViaPropSource() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertTrue(tags.contains("some_key:my.value"));
  }

  @Test
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{env}MY_ENV_VAR:my_tag")
  void shouldResolveProcessTagFromEnvironmentVariableViaEnvSource() {
    ProcessTags.envGetter = key -> "MY_ENV_VAR".equals(key) ? "myvalue" : null;
    try {
      ProcessTags.reset();

      List<String> tags = ProcessTags.getTagsAsStringList();

      assertTrue(tags.contains("my_tag:myvalue"));
    } finally {
      ProcessTags.envGetter = originalEnvGetter;
      ProcessTags.reset();
    }
  }

  @Test
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{prop}nonexistent.key:should_not_appear")
  void shouldNotEmitProcessTagWhenKeyIsMissingFromSource() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertNoTagStartingWith("should_not_appear:", tags);
  }

  @Test
  @WithConfig(key = "empty.key", value = "", addPrefix = false)
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{prop}empty.key:should_not_appear")
  void shouldNotEmitProcessTagWhenValueIsEmpty() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertNoTagStartingWith("should_not_appear:", tags);
  }

  @Test
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{unknown}some.key:should_not_appear")
  void shouldNotEmitProcessTagForUnsupportedSource() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertNoTagStartingWith("should_not_appear:", tags);
  }

  // all three cases reach parseMappingEntry and trigger a WARN
  @TableTest({
    "scenario                                                              | malformed                    ",
    "loadMap splits on first ':', key=\"no_braces\" has no {source} prefix | 'no_braces:key:bad_tag'      ",
    "key has a space, regex does not match                                 | '{unclosed_brace key:bad_tag'",
    "key is \"{prop}\" with no config-key after '}'                        | '{prop}:bad_tag'             "
  })
  void shouldIgnoreMalformedProcessTagMappingEntries(String malformed) {
    injectSysConfig(PROCESS_TAGS_MAPPING, malformed);
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertNoTagStartingWith("bad_tag:", tags);
  }

  @Test
  @WithConfig(key = "my.key", value = "first_value", addPrefix = false)
  // getMergedMap keeps the last value for the same map key
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{prop}my.key:tag_v1,{prop}my.key:tag_v2")
  void lastMappingWinsWhenSameSourceKeyIsRepeated() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertTrue(tags.contains("tag_v2:first_value"));
    assertNoTagStartingWith("tag_v1:", tags);
  }

  @Test
  @WithConfig(key = "prop.a", value = "valueA", addPrefix = false)
  @WithConfig(key = "prop.b", value = "valueB", addPrefix = false)
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{prop}prop.a:tag_a,{prop}prop.b:tag_b")
  void shouldHandleMultipleValidMappingEntries() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    assertTrue(tags.contains("tag_a:valuea"));
    assertTrue(tags.contains("tag_b:valueb"));
  }

  @Test
  @WithConfig(key = "my.prop", value = "some_value", addPrefix = false)
  @WithConfig(key = PROCESS_TAGS_MAPPING, value = "{prop}my.prop:1Tenant")
  void shouldNormalizeMappedProcessTagKey() {
    ProcessTags.reset();

    List<String> tags = ProcessTags.getTagsAsStringList();

    // key '1Tenant' is valid (leading digit is allowed) but the colon in raw keys would be invalid;
    // normalizeTagValue strips leading non-alphanumeric chars and replaces colons — verify the key
    // is stored normalized
    assertTrue(tags.stream().anyMatch(tag -> tag.startsWith("1tenant:")));
    assertNoTagStartingWith("1Tenant:", tags);
  }

  @TableTest({
    "testValue                                         | expectedValue                        ",
    "'#test_starting_hash'                             | 'test_starting_hash'                 ",
    "'TestCAPSandSuch'                                 | 'testcapsandsuch'                    ",
    "'Test Conversion Of Weird !@#$%^&**() Characters' | 'test_conversion_of_weird_characters'",
    "'$#weird_starting'                                | 'weird_starting'                     ",
    "'disallowed:c0l0ns'                               | 'disallowed_c0l0ns'                  ",
    "'1love'                                           | '1love'                              ",
    "'123456'                                          | '123456'                             ",
    "'7.0'                                             | '7.0'                                ",
    "'ünicöde'                                         | 'ünicöde'                            ",
    "'ünicöde:metäl'                                   | 'ünicöde_metäl'                      ",
    "'Data🐨dog🐶 繋がっ⛰てて'                        | 'data_dog_繋がっ_てて'               ",
    "' spaces   '                                      | 'spaces'                             ",
    "' #hashtag!@#spaces #__<>#  '                     | 'hashtag_spaces'                     ",
    "':testing'                                        | 'testing'                            ",
    "'_foo'                                            | 'foo'                                ",
    "':::test'                                         | 'test'                               ",
    "'contiguous_____underscores'                      | 'contiguous_underscores'             ",
    "'foo_'                                            | 'foo'                                ",
    "''                                                | ''                                   ",
    "' '                                               | ''                                   ",
    "'ok'                                              | 'ok'                                 ",
    "'AlsO:ök'                                         | 'also_ök'                            ",
    "':still_ok'                                       | 'still_ok'                           ",
    "'___trim'                                         | 'trim'                               ",
    "'fun:ky__tag/1'                                   | 'fun_ky_tag/1'                       ",
    "'fun:ky@tag/2'                                    | 'fun_ky_tag/2'                       ",
    "'fun:ky@@@tag/3'                                  | 'fun_ky_tag/3'                       ",
    "'tag:1/2.3'                                       | 'tag_1/2.3'                          ",
    "'---fun:k####y_ta@#g/1_@@#'                       | 'fun_k_y_ta_g/1'                     ",
    "'AlsO:œ#@ö))œk'                                   | 'also_œ_ö_œk'                        ",
    "' regulartag '                                    | 'regulartag'                         ",
    "'ſodd_ſcaseſ'                                     | 'ſodd_ſcaseſ'                        ",
    "'™Ö™Ö™™Ö™'                                        | 'ö_ö_ö'                              ",
    "'a�'                                              | 'a'                                  ",
    "'a��'                                             | 'a'                                  ",
    "'a��b'                                            | 'a_b'                                "
  })
  void processTagValueNormalization(String testValue, String expectedValue) {
    ProcessTags.addTag("test", testValue);

    List<String> tags = ProcessTags.getTagsAsStringList();
    assertNotNull(tags);
    assertEquals(
        "test:" + expectedValue,
        tags.stream().filter(tag -> tag.startsWith("test:")).findFirst().orElse(null));
  }

  /** Mirrors Groovy's {@code tags =~ expected} truthiness: the pattern is found in the tags. */
  private static void assertMatches(String expected, CharSequence tags) {
    assertTrue(
        Pattern.compile(expected).matcher(tags).find(),
        () -> "'" + tags + "' does not match '" + expected + "'");
  }

  private static void assertNoTagStartingWith(String prefix, List<String> tags) {
    assertFalse(tags.stream().anyMatch(tag -> tag.startsWith(prefix)), tags::toString);
  }
}
