package com.stocktracker.alert;

public class InvalidAlertException extends RuntimeException {
    public InvalidAlertException(String message) {
        super(message);
    }
}
