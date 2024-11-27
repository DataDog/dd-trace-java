package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestSucceedBeforeClassAfterClass {
  @BeforeClass
  public static void classSetup() {}

  @AfterClass
  public static void classTeardown() {}

  @Test
  public void testSucceed() {
    assertTrue(true);
  }
}
