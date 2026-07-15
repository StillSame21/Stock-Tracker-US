package com.stocktracker.notify;

public interface NotificationChannel {
    /** Matches {@code Notification.channel} — "EMAIL", "WEBHOOK", etc. */
    String name();

    void send(Notification notification) throws NotificationSendException;
}
