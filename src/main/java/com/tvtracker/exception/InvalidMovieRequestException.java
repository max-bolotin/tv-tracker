package com.tvtracker.exception;

public class InvalidMovieRequestException extends RuntimeException {
    public InvalidMovieRequestException(String message) {
        super(message);
    }
}
