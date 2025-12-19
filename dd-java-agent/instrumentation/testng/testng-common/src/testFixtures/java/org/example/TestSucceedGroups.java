package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

@Test(groups = "classGroup")
public class TestSucceedGroups extends Parent {

  @Test(groups = "testCaseGroup")
  public void test_succeed() {
    assertTrue(true);
  }
}

@Test(groups = "parentGroup")
class Parent {}
