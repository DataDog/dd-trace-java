package datadog.smoke.calculator;

import static org.junit.Assert.assertEquals;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class CalculatorSteps {

  private int result;

  @Given("a calculator I just turned on")
  public void a_calculator_I_just_turned_on() {
    result = 0;
  }

  @When("I add {int} and {int}")
  public void adding(int a, int b) {
    result = a + b;
  }

  @Then("the result is {int}")
  public void the_result_is(int expected) {
    // Deterministically fails (4 + 5 != 8), so auto-test-retry re-runs the scenario.
    assertEquals(expected, result);
  }
}
