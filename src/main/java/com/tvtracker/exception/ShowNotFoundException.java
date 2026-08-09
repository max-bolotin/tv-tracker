package com.tvtracker.exception;

public class ShowNotFoundException extends RuntimeException {
    public ShowNotFoundException(String id) {
        super("Show not found: " + id);
    }
}
