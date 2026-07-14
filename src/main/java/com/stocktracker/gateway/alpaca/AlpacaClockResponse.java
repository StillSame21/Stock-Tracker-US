package com.stocktracker.gateway.alpaca;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

record AlpacaClockResponse(
        Instant timestamp,
        @JsonProperty("is_open") boolean isOpen,
        @JsonProperty("next_open") Instant nextOpen,
        @JsonProperty("next_close") Instant nextClose
) {
}
