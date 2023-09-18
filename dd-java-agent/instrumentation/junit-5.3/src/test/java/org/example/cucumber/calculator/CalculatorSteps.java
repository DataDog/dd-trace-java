package org.example.cucumber.calculator;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class CalculatorSteps {

  private Calculator calc;

  @Given("a calculator I just turned on")
  public void a_calculator_I_just_turned_on() {
    calc = new Calculator();
  }

  @When("I add {int} and {int}")
  public void adding(int arg1, int arg2) {
    calc.push(arg1);
    calc.push(arg2);
    calc.push("+");
  }

  @Given("^I press (.+)$")
  public void I_press(String what) {
    calc.push(what);
  }

  @Then("the result is {int}")
  public void the_result_is(double expected) {
    assertEquals(expected, calc.value());
  }

  @Given("the previous entries:")
  public void thePreviousEntries(List<Entry> entries) {
    for (Entry entry : entries) {
      calc.push(entry.first);
      calc.push(entry.second);
      calc.push(entry.operation);
    }
  }

  static final class Entry {

    private Integer first;
    private Integer second;
    private String operation;

    public Integer getFirst() {
      return first;
    }

    public void setFirst(Integer first) {
      this.first = first;
    }

    public Integer getSecond() {
      return second;
    }

    public void setSecond(Integer second) {
      this.second = second;
    }

    public String getOperation() {
      return operation;
    }

    public void setOperation(String operation) {
      this.operation = operation;
    }
  }

  static class Calculator {
    private static final List<String> OPS = asList("-", "+", "*", "/");
    private final Deque<Number> stack = new LinkedList<>();

    public void push(Object arg) {
      if (OPS.contains(arg)) {
        Number y = stack.removeLast();
        Number x = stack.isEmpty() ? 0 : stack.removeLast();
        Double val = null;
        if (arg.equals("-")) {
          val = x.doubleValue() - y.doubleValue();
        } else if (arg.equals("+")) {
          val = x.doubleValue() + y.doubleValue();
        } else if (arg.equals("*")) {
          val = x.doubleValue() * y.doubleValue();
        } else if (arg.equals("/")) {
          val = x.doubleValue() / y.doubleValue();
        }
        push(val);
      } else {
        stack.add((Number) arg);
      }
    }

    public Number value() {
      return stack.getLast();
    }
  }
}
