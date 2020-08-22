/**
 * Please note that the package name here needs to match where org.slf4j.impl will be in the
 * shadowed jar.
 */
package datadog.slf4j.impl;

import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MarkerFactoryBinder;

public class StaticMarkerBinder implements MarkerFactoryBinder {

  public static final StaticMarkerBinder SINGLETON = new StaticMarkerBinder();

  final IMarkerFactory markerFactory = new BasicMarkerFactory();

  private StaticMarkerBinder() {}

  public static StaticMarkerBinder getSingleton() {
    return SINGLETON;
  }

  public IMarkerFactory getMarkerFactory() {
    return markerFactory;
  }

  public String getMarkerFactoryClassStr() {
    return BasicMarkerFactory.class.getName();
  }
}
