package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestSucceedBeforeAfter {
  @Before
  public void testSetup() {}

  @After
  public void testTeardown() {}

  @Test
  public void testSucceed() {
    assertTrue(true);
  }
}
