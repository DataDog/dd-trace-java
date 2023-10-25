package org.example;

import io.cucumber.core.options.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource(
    "org/example/cucumber/calculator/basic_arithmetic_with_examples_skipped.feature")
@ConfigurationParameter(
    key = Constants.GLUE_PROPERTY_NAME,
    value = "org.example.cucumber.calculator")
@ConfigurationParameter(key = Constants.FILTER_TAGS_PROPERTY_NAME, value = "not @Disabled")
public class TestSkippedExamplesCucumber {}
