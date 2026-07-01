package datadog.metrics.impl;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.metrics.impl.DDSketchHistograms.ExplicitBoundaries;
import java.util.List;
import org.junit.jupiter.api.Test;

class DDSketchHistogramsTest {

  // boundaries define 4 bins:
  //   bin 0: (-inf, 10]
  //   bin 1: (10,   20]
  //   bin 2: (20,   30]
  //   bin 3: (30,  +inf)
  private static final List<Double> BOUNDARIES = asList(10.0, 20.0, 30.0);

  @Test
  void indexBelowFirstBoundary() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(0, eb.index(5.0));
  }

  @Test
  void indexOnBoundary() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(0, eb.index(10.0));
    assertEquals(1, eb.index(20.0));
    assertEquals(2, eb.index(30.0));
  }

  @Test
  void indexBetweenBoundaries() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(1, eb.index(15.0));
    assertEquals(2, eb.index(25.0));
  }

  @Test
  void indexAboveLastBoundary() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(3, eb.index(35.0));
  }

  @Test
  void lowerBoundOfFirstBinIsNegativeInfinity() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(Double.NEGATIVE_INFINITY, eb.lowerBound(0));
  }

  @Test
  void lowerBoundMapsToPreceeedingBoundary() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(10.0, eb.lowerBound(1));
    assertEquals(20.0, eb.lowerBound(2));
    assertEquals(30.0, eb.lowerBound(3));
  }

  @Test
  void upperBoundMapsToCorrespondingBoundary() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(10.0, eb.upperBound(0));
    assertEquals(20.0, eb.upperBound(1));
    assertEquals(30.0, eb.upperBound(2));
  }

  @Test
  void upperBoundOfLastBinIsPositiveInfinity() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(Double.POSITIVE_INFINITY, eb.upperBound(3));
  }

  @Test
  void extremeIndexableValues() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertEquals(Double.NEGATIVE_INFINITY, eb.minIndexableValue());
    assertEquals(Double.POSITIVE_INFINITY, eb.maxIndexableValue());
  }

  @Test
  void unsupportedOperationsThrow() {
    ExplicitBoundaries eb = new ExplicitBoundaries(BOUNDARIES);
    assertThrows(UnsupportedOperationException.class, () -> eb.value(0));
    assertThrows(UnsupportedOperationException.class, eb::relativeAccuracy);
    assertThrows(UnsupportedOperationException.class, () -> eb.encode(null));
    assertThrows(UnsupportedOperationException.class, eb::serializedSize);
    assertThrows(UnsupportedOperationException.class, () -> eb.serialize(null));
  }
}
