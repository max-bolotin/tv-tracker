package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum MediaType {
    TV,
    MOVIE;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static MediaType fromJson(String value) {
        return value == null || value.isBlank()
                ? null
                : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
