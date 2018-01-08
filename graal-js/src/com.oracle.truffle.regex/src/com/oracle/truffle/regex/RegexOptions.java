/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex;

import com.oracle.truffle.api.CompilerDirectives;

import java.util.Arrays;

public final class RegexOptions {

    private static final int U180E_WHITESPACE = 1;
    private static final int USE_JONI = 1 << 1;

    public static final RegexOptions DEFAULT = new RegexOptions(0);

    private final int options;

    private RegexOptions(int options) {
        this.options = options;
    }

    @CompilerDirectives.TruffleBoundary
    public static RegexOptions parse(String optionsString) throws RegexSyntaxException {
        int options = 0;
        for (String propValue : optionsString.split(",")) {
            if (propValue.isEmpty()) {
                continue;
            }
            int eqlPos = propValue.indexOf('=');
            if (eqlPos < 0) {
                throw optionsSyntaxError(optionsString, propValue + " is not in form 'key=value'");
            }
            String key = propValue.substring(0, eqlPos);
            String value = propValue.substring(eqlPos + 1);
            switch (key) {
                case "U180EWhitespace":
                    if (value.equals("true")) {
                        options |= U180E_WHITESPACE;
                    } else if (!value.equals("false")) {
                        throw optionsSyntaxErrorUnexpectedValue(optionsString, key, value, "true", "false");
                    }
                    break;
                case "Engine":
                    switch (value) {
                        case "tregex":
                            break;
                        case "joni":
                            options |= USE_JONI;
                            break;
                        default:
                            throw optionsSyntaxErrorUnexpectedValue(optionsString, key, value, "joni");
                    }
                    break;
                default:
                    throw optionsSyntaxError(optionsString, "unexpected option " + key);
            }
        }
        return new RegexOptions(options);
    }

    private static RegexSyntaxException optionsSyntaxErrorUnexpectedValue(String optionsString, String key, String value, String... expectedValues) {
        return optionsSyntaxError(optionsString, String.format("unexpected value '%s' for option '%s', expected one of %s", value, key, Arrays.toString(expectedValues)));
    }

    private static RegexSyntaxException optionsSyntaxError(String optionsString, String msg) {
        return new RegexSyntaxException(String.format("Invalid options syntax in '%s': %s", optionsString, msg));
    }

    private boolean isBitSet(int bit) {
        return (options & bit) != 0;
    }

    public boolean useJoniEngine() {
        return isBitSet(USE_JONI);
    }

    public boolean isU180EWhitespace() {
        return isBitSet(U180E_WHITESPACE);
    }

    @Override
    public int hashCode() {
        return options;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || obj instanceof RegexOptions && options == ((RegexOptions) obj).options;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isU180EWhitespace()) {
            sb.append("U180EWhitespace");
        }
        if (useJoniEngine()) {
            if (isU180EWhitespace()) {
                sb.append(",");
            }
            sb.append("useJoni");
        }
        return sb.toString();
    }
}