package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.telemetry.api.Integration;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ExtendedHeartbeatDataTest {

  private final Dependency dependency = new Dependency("name", "version", "source", "hash");
  private final ConfigSetting configSetting =
      ConfigSetting.of("key", "value", ConfigOrigin.DEFAULT);
  private final Integration integration = new Integration("integration", true);

  @TableTest({
    "scenario    | limit",
    "no limit    | 0    ",
    "small limit | 2    ",
    "large limit | 10   "
  })
  void discardDependenciesAfterExceedingLimit(int limit) {
    ExtendedHeartbeatData extHeartbeatData = new ExtendedHeartbeatData(limit);

    for (int pushed = 0; pushed < limit + 1; pushed++) {
      extHeartbeatData.pushDependency(dependency);
    }

    EventSource snapshot = extHeartbeatData.snapshot();
    int dependencyCount = 0;
    while (snapshot.hasDependencyEvent()) {
      snapshot.nextDependencyEvent();
      dependencyCount++;
    }
    assertEquals(limit, dependencyCount);
  }

  @Test
  void returnAllCollectedData() {
    ExtendedHeartbeatData extHeartbeatData = new ExtendedHeartbeatData();

    // when
    EventSource emptySnapshot = extHeartbeatData.snapshot();
    // then
    assertTrue(emptySnapshot.isEmpty());

    // when
    extHeartbeatData.pushDependency(dependency);
    extHeartbeatData.pushConfigSetting(configSetting);
    extHeartbeatData.pushIntegration(integration);

    // then
    EventSource snapshot = extHeartbeatData.snapshot();

    assertFalse(snapshot.isEmpty());

    assertTrue(snapshot.hasDependencyEvent());
    assertEquals(dependency, snapshot.nextDependencyEvent());
    assertFalse(snapshot.hasDependencyEvent());

    assertFalse(snapshot.isEmpty());

    assertTrue(snapshot.hasConfigChangeEvent());
    assertEquals(configSetting, snapshot.nextConfigChangeEvent());
    assertFalse(snapshot.hasConfigChangeEvent());

    assertFalse(snapshot.isEmpty());

    assertTrue(snapshot.hasIntegrationEvent());
    assertEquals(integration, snapshot.nextIntegrationEvent());
    assertFalse(snapshot.hasIntegrationEvent());

    assertTrue(snapshot.isEmpty());

    // when another snapshot includes all data
    EventSource anotherSnapshot = extHeartbeatData.snapshot();

    // then
    assertFalse(anotherSnapshot.isEmpty());
    assertTrue(anotherSnapshot.hasDependencyEvent());
    assertTrue(anotherSnapshot.hasConfigChangeEvent());
    assertTrue(anotherSnapshot.hasIntegrationEvent());
  }
}
