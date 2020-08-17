/**
 * Please note that the package name here needs to match where org.slf4j.impl will be in the
 * shadowed jar.
 */
package datadog.slf4j.impl;

import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

public class StaticMDCBinder {

  public static final StaticMDCBinder SINGLETON = new StaticMDCBinder();

  private StaticMDCBinder() {}

  public MDCAdapter getMDCA() {
    return new NOPMDCAdapter();
  }

  public String getMDCAdapterClassStr() {
    return NOPMDCAdapter.class.getName();
  }
}
