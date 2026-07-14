/**
 * HTTP + STOMP surface (Steps 1, 7): REST controllers for quotes, symbols,
 * watchlists, alerts and notifications, plus the WebSocket/STOMP broadcaster
 * that pushes live quotes to the UI (throttled to 1 update/symbol/second).
 */
package com.stocktracker.api;
