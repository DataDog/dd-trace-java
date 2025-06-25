package com.datadog.convention;

import java.util.List;

public class Checker {
    public static void main(String[] args) {
        String baseDirectory = ".."; // Change this to the root directory of your project
        InstrumentationValidator validator = new InstrumentationValidator();
        
        List<Issue> issues = validator.validate(baseDirectory);
        
        if (!issues.isEmpty()) {
            issues.forEach(System.out::println);
        } else {
            System.out.println("All checks passed!");
        }
    }
}
