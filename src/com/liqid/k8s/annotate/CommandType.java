/**
 * k8s-integration
 * Copyright 2023 by Liqid, Inc - All Rights Reserved
 */

package com.liqid.k8s.annotate;

import java.util.Arrays;

public enum CommandType {
    AUTO("auto"),
    LABEL("label"),
    LINK("link"),
    NODES("nodes"),
    RESOURCES("resources"),
    UNLABEL("unlabel"),
    UNLINK("unlink");

    private final String _token;

    CommandType(final String value) { _token = value; }

    public String getToken() { return _token; }

    public static CommandType get(
        final String token
    ) {
        return Arrays.stream(values()).filter(ct -> ct.getToken().equals(token)).findFirst().orElse(null);
    }
}
