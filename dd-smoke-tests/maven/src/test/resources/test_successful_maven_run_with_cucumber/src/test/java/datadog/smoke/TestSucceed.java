package datadog.smoke;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
    features = "classpath:datadog/smoke/basic_arithmetic.feature",
    glue = "datadog.smoke.calculator")
public class TestSucceed {
}
