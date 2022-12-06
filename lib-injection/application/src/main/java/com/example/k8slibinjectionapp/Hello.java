package com.example.k8slibinjectionapp;

public class Hello {
    private String message;


    public Hello() {
        this.message = "Hello World";
    }

    public Hello(String message) {
        this.message = message;
    }


    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
