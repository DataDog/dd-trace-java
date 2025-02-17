{
    "data": {
        "id": "9p1jTQLXB8g",
        "type": "ci_app_libraries_tests",
        "attributes": {
            "modules": {
                "module-a": {
                    "suites": {
                        "suite-a": {
                            "tests": {
                                "test-a": {
                                    "properties": {
                                        "quarantined": true,
                                        "disabled": false,
                                        "attempt_to_fix": false
                                    }
                                },
                                "test-b": {
                                    "properties": {
                                        "quarantined": false,
                                        "disabled": true,
                                        "attempt_to_fix": false
                                    }
                                }
                            }
                        },
                        "suite-b": {
                            "tests": {
                                "test-c": {
                                    "properties": {
                                        "quarantined": true,
                                        "disabled": false,
                                        "attempt_to_fix": true
                                    }
                                }
                            }
                        }
                    }
                },
                "module-b": {
                    "suites": {
                        "suite-c": {
                            "tests": {
                                "test-d": {
                                    "properties": {
                                        "quarantined": false,
                                        "disabled": true,
                                        "attempt_to_fix": true
                                    }
                                },
                                "test-e": {
                                    "properties": {
                                        "quarantined": true,
                                        "attempt_to_fix": true
                                    }
                                },
                                "test-f": {
                                    "properties": {
                                        "disabled": true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
