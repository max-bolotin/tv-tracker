package com.tvtracker.exception;

public class InvalidImportException extends RuntimeException {
    public InvalidImportException(String message) {
        super(message);
    }

    public InvalidImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
