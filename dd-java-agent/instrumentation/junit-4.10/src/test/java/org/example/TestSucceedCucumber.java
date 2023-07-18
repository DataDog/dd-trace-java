package org.example;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
    features = "classpath:org/example/cucumber/calculator/basic_arithmetic.feature",
    glue = "org.example.cucumber.calculator",
    strict = true)
public class TestSucceedCucumber {}
