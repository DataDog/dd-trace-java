package datadog.trace.civisibility.coverage.probe.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * Code for each part of Shared: an initializer, a suite setup step and a teardown step. Only the
 * test of the Shared report runs it, so the lines of this file in each part come from that test.
 */
public final class SharedPartsWorkload {

  static final List<String> NAMES = new ArrayList<>();

  static {
    NAMES.add("init");
  }

  private SharedPartsWorkload() {}

  public static int setUp() {
    NAMES.add("setup");
    return NAMES.size();
  }

  public static int tearDown() {
    NAMES.clear();
    return 0;
  }
}
