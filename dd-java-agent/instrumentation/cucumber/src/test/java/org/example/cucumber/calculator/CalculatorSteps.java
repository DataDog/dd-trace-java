package org.example.cucumber.calculator;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

  @Then("the result is {int}")
  public void the_result_is(double expected) {
    assertEquals(expected, calc.value());
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
