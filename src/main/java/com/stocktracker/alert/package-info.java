/**
 * Alert engine (Step 5): alert model, ARMED/TRIGGERED state machine with
 * re-arm hysteresis and cooldown, idempotent evaluation per
 * {@code (alert_id, bar_timestamp)}. Reads the price stream directly —
 * never the UI quote cache (L3.1).
 */
package com.stocktracker.alert;
