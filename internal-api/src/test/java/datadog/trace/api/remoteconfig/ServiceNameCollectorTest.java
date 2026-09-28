package datadog.trace.api.remoteconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ServiceNameCollectorTest {

  @Test
  void testAddExtraService() {
    ServiceNameCollector provider = new ServiceNameCollector();
    String service = "testService";

    provider.addService(service);

    assertEquals(service, provider.getServices().get(0));
  }

  @TableTest({
    "scenario     | value",
    "null value   |      ",
    "empty string | ''   "
  })
  void testAddInvalidExtraService(String value) {
    ServiceNameCollector provider = new ServiceNameCollector();

    provider.addService(value);

    assertNull(provider.getServices());
  }

  @Test
  void extraServiceIsNotAddedIfItIsTheDefaultOne() {
    ServiceNameCollector provider = new ServiceNameCollector();
    String global = Config.get().getServiceName();

    provider.addService(global);

    assertNotNull(global);
    assertNull(provider.getServices());
  }

  @TableTest({
    "scenario       | service      ",
    "same case      | 'testService'",
    "different case | 'TestService'"
  })
  void extraServiceIsNotAddedIfAlreadyExist(String service) {
    ServiceNameCollector provider = new ServiceNameCollector();
    provider.addService("testService");
    assertEquals(1, provider.getServices().size());

    provider.addService(service);

    assertEquals(1, provider.getServices().size());
  }

  @Test
  void extraServiceCanNotExceed64Elements() {
    ServiceNameCollector provider = new ServiceNameCollector();
    assertFalse(provider.limitReachedLogged);
    for (int i = 0; i <= 64; i++) {
      provider.addService("testService" + i);
    }
    assertEquals(64, provider.getServices().size());
    assertTrue(provider.limitReachedLogged);

    provider.addService("testService");

    assertEquals(64, provider.getServices().size());
  }

  @Test
  void getExtraServicesReturnsNullIfThereAreNoExtraServices() {
    ServiceNameCollector provider = new ServiceNameCollector();

    List<String> result = provider.getServices();

    assertNull(result);
  }
}
