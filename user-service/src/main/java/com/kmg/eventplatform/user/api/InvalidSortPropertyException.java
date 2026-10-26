package com.kmg.eventplatform.user.api;

import java.util.Set;
import java.util.TreeSet;

class InvalidSortPropertyException extends RuntimeException {

    private final String property;

    InvalidSortPropertyException(String property, Set<String> allowed) {
        super("Unknown sort property '" + property + "', allowed: " + new TreeSet<>(allowed));
        this.property = property;
    }

    String getProperty() {
        return property;
    }
}
