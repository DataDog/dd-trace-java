package datadog.remoteconfig.state;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.ReportableException;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductStateSpecification {

  private final PollingRateHinter hinter = mock(PollingRateHinter.class);

  @Test
  void testApplyForNonAsmDdProductAppliesChangesBeforeRemoves() {
    // a ProductState for ASM_DATA
    ProductState productState = new ProductState(Product.ASM_DATA);
    OrderRecordingListener listener = new OrderRecordingListener();
    productState.addProductListener(listener);

    // first apply with config1 to cache it
    RemoteConfigResponse response1 =
        buildResponse(targets("org/ASM_DATA/config1/foo", new TargetSpec(1, 8, "oldhash1")));
    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DATA/config1/foo");
    productState.apply(response1, Collections.singletonList(key1), hinter);
    listener.operations.clear(); // Clear for the actual test

    // a new response with config1 (changed hash) and config2 (new)
    RemoteConfigResponse response2 =
        buildResponse(
            targets(
                "org/ASM_DATA/config1/foo", new TargetSpec(2, 8, "newhash1"),
                "org/ASM_DATA/config2/foo", new TargetSpec(1, 8, "hash2")));
    ParsedConfigKey key2 = ParsedConfigKey.parse("org/ASM_DATA/config2/foo");

    // apply is called
    boolean changed = productState.apply(response2, Arrays.asList(key1, key2), hinter);

    // changes are detected
    assertTrue(changed);

    // operations happen in order: apply config1, apply config2, commit (no removes)
    assertEquals(
        Arrays.asList(
            "accept:org/ASM_DATA/config1/foo", "accept:org/ASM_DATA/config2/foo", "commit"),
        listener.operations);
  }

  @Test
  void testApplyForAsmDdProductAppliesChangesAfterRemoves() {
    // a ProductState for ASM_DD
    ProductState productState = new ProductState(Product.ASM_DD);
    OrderRecordingListener listener = new OrderRecordingListener();
    productState.addProductListener(listener);

    // first apply with config1 and config2 to cache them
    RemoteConfigResponse response1 =
        buildResponse(
            targets(
                "org/ASM_DD/config1/foo", new TargetSpec(1, 8, "oldhash1"),
                "org/ASM_DD/config2/foo", new TargetSpec(1, 8, "hash2")));
    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DD/config1/foo");
    ParsedConfigKey key2 = ParsedConfigKey.parse("org/ASM_DD/config2/foo");
    productState.apply(response1, Arrays.asList(key1, key2), hinter);
    listener.operations.clear(); // Clear for the actual test

    // a new response with only config1 (changed hash) - config2 will be removed
    RemoteConfigResponse response2 =
        buildResponse(targets("org/ASM_DD/config1/foo", new TargetSpec(2, 8, "newhash1")));

    // apply is called
    boolean changed = productState.apply(response2, Collections.singletonList(key1), hinter);

    // changes are detected
    assertTrue(changed);

    // operations happen in order: remove config2 FIRST, then apply config1, then commit
    assertEquals(
        Arrays.asList("remove:org/ASM_DD/config2/foo", "accept:org/ASM_DD/config1/foo", "commit"),
        listener.operations);
  }

  @Test
  void testAsmDdWithMultipleNewConfigsRemovesBeforeAppliesAll() {
    // a ProductState for ASM_DD
    ProductState productState = new ProductState(Product.ASM_DD);
    OrderRecordingListener listener = new OrderRecordingListener();
    productState.addProductListener(listener);

    // first apply with old configs
    RemoteConfigResponse response1 =
        buildResponse(
            targets(
                "org/ASM_DD/old1/foo", new TargetSpec(1, 8, "hash_old1"),
                "org/ASM_DD/old2/foo", new TargetSpec(1, 8, "hash_old2")));
    ParsedConfigKey oldKey1 = ParsedConfigKey.parse("org/ASM_DD/old1/foo");
    ParsedConfigKey oldKey2 = ParsedConfigKey.parse("org/ASM_DD/old2/foo");
    productState.apply(response1, Arrays.asList(oldKey1, oldKey2), hinter);
    listener.operations.clear(); // Clear for the actual test

    // a response with completely new configs
    RemoteConfigResponse response2 =
        buildResponse(
            targets(
                "org/ASM_DD/new1/foo", new TargetSpec(1, 8, "hash_new1"),
                "org/ASM_DD/new2/foo", new TargetSpec(1, 8, "hash_new2")));
    ParsedConfigKey newKey1 = ParsedConfigKey.parse("org/ASM_DD/new1/foo");
    ParsedConfigKey newKey2 = ParsedConfigKey.parse("org/ASM_DD/new2/foo");

    // apply is called
    boolean changed = productState.apply(response2, Arrays.asList(newKey1, newKey2), hinter);

    // changes are detected
    assertTrue(changed);

    // all removes happen before all applies
    assertEquals(5, listener.operations.size()); // 2 removes + 2 accepts + 1 commit
    assertEquals(2, countStartingWith(listener.operations, "remove:"));
    assertEquals(2, countStartingWith(listener.operations, "accept:"));

    // removes come before accepts
    int lastRemoveIdx = lastIndexStartingWith(listener.operations, "remove:");
    int firstAcceptIdx = firstIndexStartingWith(listener.operations, "accept:");
    assertTrue(lastRemoveIdx < firstAcceptIdx);
  }

  @Test
  void testNoChangesDetectedWhenConfigHashesMatch() {
    // a ProductState
    ProductState productState = new ProductState(Product.ASM_DATA);
    OrderRecordingListener listener = new OrderRecordingListener();
    productState.addProductListener(listener);

    // first apply with a config
    RemoteConfigResponse response =
        buildResponse(targets("org/ASM_DATA/config1/foo", new TargetSpec(1, 8, "hash1")));
    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DATA/config1/foo");
    productState.apply(response, Collections.singletonList(key1), hinter);
    listener.operations.clear(); // Clear for the actual test

    // apply is called again with the same hash
    boolean changed = productState.apply(response, Collections.singletonList(key1), hinter);

    // no changes are detected
    assertFalse(changed);

    // no listener operations occurred
    assertTrue(listener.operations.isEmpty());
  }

  @Test
  void testErrorHandlingDuringApply() throws Exception {
    // a ProductState
    ProductState productState = new ProductState(Product.ASM_DATA);
    ProductListener listener = mock(ProductListener.class);
    productState.addProductListener(listener);

    // a response with a config
    RemoteConfigResponse response =
        buildResponse(targets("org/ASM_DATA/config1/foo", new TargetSpec(1, 8, "hash1")));

    // listener throws an exception
    doThrow(new RuntimeException("Listener error")).when(listener).accept(any(), any(), any());

    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DATA/config1/foo");

    // apply is called
    boolean changed = productState.apply(response, Collections.singletonList(key1), hinter);

    // changes are still detected
    assertTrue(changed);

    // commit is still called despite the error
    verify(listener).commit(hinter);
  }

  @Test
  void testReportableExceptionIsRecorded() throws Exception {
    // a ProductState
    ProductState productState = new ProductState(Product.ASM_DATA);
    ProductListener listener = mock(ProductListener.class);
    productState.addProductListener(listener);

    // a response with a config
    RemoteConfigResponse response =
        buildResponse(targets("org/ASM_DATA/config1/foo", new TargetSpec(1, 8, "hash1")));

    // listener throws a ReportableException
    ReportableException exception = new ReportableException("Test error");
    doThrow(exception).when(listener).accept(any(), any(), any());

    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DATA/config1/foo");

    // apply is called
    productState.apply(response, Collections.singletonList(key1), hinter);

    // error is recorded
    assertTrue(productState.hasError());
    assertTrue(productState.getErrors().contains(exception));
  }

  @Test
  void testConfigListenersAreCalledInAdditionToProductListeners() {
    // a ProductState
    ProductState productState = new ProductState(Product.ASM_DATA);
    OrderRecordingListener productListener = new OrderRecordingListener();
    OrderRecordingListener configListener = new OrderRecordingListener();
    productState.addProductListener(productListener);
    productState.addProductListener("config1", configListener);

    // a response with two configs
    RemoteConfigResponse response =
        buildResponse(
            targets(
                "org/ASM_DATA/config1/foo", new TargetSpec(1, 8, "hash1"),
                "org/ASM_DATA/config2/foo", new TargetSpec(1, 8, "hash2")));

    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DATA/config1/foo");
    ParsedConfigKey key2 = ParsedConfigKey.parse("org/ASM_DATA/config2/foo");

    // apply is called
    productState.apply(response, Arrays.asList(key1, key2), hinter);

    // productListener received both configs
    assertEquals(2, countStartingWith(productListener.operations, "accept:"));

    // configListener only received config1
    assertEquals(
        Arrays.asList("accept:org/ASM_DATA/config1/foo", "commit"), configListener.operations);
  }

  @Test
  void testRemoveOperationsCleanupCachedData() throws Exception {
    // a ProductState
    ProductState productState = new ProductState(Product.ASM_DATA);
    ProductListener listener = mock(ProductListener.class);
    productState.addProductListener(listener);

    // first apply with a config to cache it
    RemoteConfigResponse response1 =
        buildResponse(targets("org/ASM_DATA/config1/foo", new TargetSpec(1, 8, "hash1")));
    ParsedConfigKey key1 = ParsedConfigKey.parse("org/ASM_DATA/config1/foo");
    productState.apply(response1, Collections.singletonList(key1), hinter);

    // an empty response (config should be removed)
    RemoteConfigResponse response2 = buildResponse(Collections.emptyMap());

    // apply is called
    boolean changed =
        productState.apply(response2, Collections.<ParsedConfigKey>emptyList(), hinter);

    // changes are detected
    assertTrue(changed);

    // listener remove was called
    verify(listener).remove(key1, hinter);

    // cached data is cleaned up
    assertTrue(productState.getCachedTargetFiles().isEmpty());
    assertTrue(productState.getConfigStates().isEmpty());
  }

  // Helper methods

  private static Map<String, TargetSpec> targets(String path, TargetSpec spec) {
    return Collections.singletonMap(path, spec);
  }

  private static Map<String, TargetSpec> targets(
      String path1, TargetSpec spec1, String path2, TargetSpec spec2) {
    Map<String, TargetSpec> targets = new HashMap<>();
    targets.put(path1, spec1);
    targets.put(path2, spec2);
    return targets;
  }

  private static RemoteConfigResponse buildResponse(Map<String, TargetSpec> targets) {
    RemoteConfigResponse response = mock(RemoteConfigResponse.class);

    for (Map.Entry<String, TargetSpec> entry : targets.entrySet()) {
      String path = entry.getKey();
      TargetSpec targetData = entry.getValue();

      RemoteConfigResponse.Targets.ConfigTarget target =
          new RemoteConfigResponse.Targets.ConfigTarget();
      target.hashes = singletonMap("sha256", targetData.hash);
      target.length = targetData.length;

      RemoteConfigResponse.Targets.ConfigTarget.ConfigTargetCustom custom =
          new RemoteConfigResponse.Targets.ConfigTarget.ConfigTargetCustom();
      custom.version = targetData.version;
      target.custom = custom;

      when(response.getTarget(path)).thenReturn(target);
      when(response.getFileContents(path))
          .thenReturn(("content_" + targetData.hash).getBytes(UTF_8));
    }

    // Handle empty targets case
    if (targets.isEmpty()) {
      when(response.getTarget(any())).thenReturn(null);
    }

    return response;
  }

  private static int countStartingWith(List<String> operations, String prefix) {
    int count = 0;
    for (String operation : operations) {
      if (operation.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static int firstIndexStartingWith(List<String> operations, String prefix) {
    for (int index = 0; index < operations.size(); index++) {
      if (operations.get(index).startsWith(prefix)) {
        return index;
      }
    }
    return -1;
  }

  private static int lastIndexStartingWith(List<String> operations, String prefix) {
    for (int index = operations.size() - 1; index >= 0; index--) {
      if (operations.get(index).startsWith(prefix)) {
        return index;
      }
    }
    return -1;
  }

  // Light structure describing a target's metadata for the mocked response
  private static final class TargetSpec {
    final long version;
    final long length;
    final String hash;

    TargetSpec(long version, long length, String hash) {
      this.version = version;
      this.length = length;
      this.hash = hash;
    }
  }

  // Test helper class to record operation order
  static class OrderRecordingListener implements ProductListener {
    final List<String> operations = new ArrayList<>();

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter) {
      operations.add("accept:" + configKey.toString());
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter pollingRateHinter) {
      operations.add("remove:" + configKey.toString());
    }

    @Override
    public void commit(PollingRateHinter pollingRateHinter) {
      operations.add("commit");
    }
  }
}
