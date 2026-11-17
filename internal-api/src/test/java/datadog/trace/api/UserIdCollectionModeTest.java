package datadog.trace.api;

import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfigExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class UserIdCollectionModeTest {

  @TableTest({
    "scenario                  | mode                          | expected      ",
    "unset                     |                               | IDENTIFICATION",
    "identification            | identification                | IDENTIFICATION",
    "identification mixed case | iDeNTiFiCaTioN                | IDENTIFICATION",
    "ident alias               | ident                         | IDENTIFICATION",
    "anonymization             | anonymization                 | ANONYMIZATION ",
    "anonymization mixed case  | aNoNyMiZaTioN                 | ANONYMIZATION ",
    "anon alias                | anon                          | ANONYMIZATION ",
    "disabled                  | disabled                      | DISABLED      ",
    "disabled mixed case       | dIsAblEd                      | DISABLED      ",
    "empty                     | ''                            | DISABLED      ",
    "unknown                   | 'go loves node but java wins' | DISABLED      "
  })
  void userIdCollectionMode(String mode, UserIdCollectionMode expected) {
    UserIdCollectionMode result = UserIdCollectionMode.fromString(mode, null);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario                 | mode  | tracking | expected      ",
    // when value is not defined
    "extended tracking        |       | extended | IDENTIFICATION",
    "safe tracking            |       | safe     | ANONYMIZATION ",
    "disabled tracking        |       | disabled | DISABLED      ",
    // actual value takes priority
    "ident, no tracking       | ident |          | IDENTIFICATION",
    "ident, extended tracking | ident | extended | IDENTIFICATION",
    "ident, safe tracking     | ident | safe     | IDENTIFICATION",
    "ident, disabled tracking | ident | disabled | IDENTIFICATION",
    "anon, no tracking        | anon  |          | ANONYMIZATION ",
    "anon, extended tracking  | anon  | extended | ANONYMIZATION ",
    "anon, safe tracking      | anon  | safe     | ANONYMIZATION ",
    "anon, disabled tracking  | anon  | disabled | ANONYMIZATION ",
    // by default
    "neither set              |       |          | IDENTIFICATION"
  })
  void userIdCollectionModeWithTracking(
      String mode, String tracking, UserIdCollectionMode expected) {
    UserIdCollectionMode result = UserIdCollectionMode.fromString(mode, tracking);

    assertEquals(expected, result);
  }

  @TableTest({
    "scenario             | mode     | rc       | expected      ",
    // by default
    "neither set          |          |          | IDENTIFICATION",
    // rc wins
    "unknown rc           | ident    | yolo     | DISABLED      ",
    "disabled rc          | ident    | disabled | DISABLED      ",
    "ident rc             | anon     | ident    | IDENTIFICATION",
    "anon rc              | ident    | anon     | ANONYMIZATION ",
    // rc not present
    "unknown mode, no rc  | yolo     |          | DISABLED      ",
    "disabled mode, no rc | disabled |          | DISABLED      ",
    "ident mode, no rc    | ident    |          | IDENTIFICATION",
    "anon mode, no rc     | anon     |          | ANONYMIZATION "
  })
  void userIdCollectionModeWithRemoteConfig(String mode, String rc, UserIdCollectionMode expected) {
    if (mode == null) {
      removeSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE);
    } else {
      injectSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, mode);
    }

    UserIdCollectionMode.fromRemoteConfig(rc);

    assertEquals(expected, UserIdCollectionMode.get());
  }
}
