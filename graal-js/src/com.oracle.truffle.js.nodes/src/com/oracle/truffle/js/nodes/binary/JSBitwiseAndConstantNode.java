/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.binary;

import java.util.Objects;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.cast.JSToInt32Node;
import com.oracle.truffle.js.nodes.unary.JSUnaryNode;

@NodeInfo(shortName = "&")
@NodeField(name = "rightValue", type = int.class)
public abstract class JSBitwiseAndConstantNode extends JSUnaryNode {

    public static JSBitwiseAndConstantNode create(JavaScriptNode left, int right) {
        return JSBitwiseAndConstantNodeGen.create(left, right);
    }

    public abstract int executeInt(Object a);

    public abstract int getRightValue();

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return clazz == int.class;
    }

    @Specialization
    protected int doInteger(int a) {
        return a & getRightValue();
    }

    @Specialization(replaces = "doInteger")
    protected int doGeneric(Object a,
                    @Cached("create()") JSToInt32Node leftInt32) {
        return doInteger(leftInt32.executeInt(a));
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return JSBitwiseAndConstantNodeGen.create(cloneUninitialized(getOperand()), getRightValue());
    }

    @Override
    public String expressionToString() {
        if (getOperand() != null) {
            return "(" + Objects.toString(getOperand().expressionToString(), INTERMEDIATE_VALUE) + " & " + getRightValue() + ")";
        }
        return null;
    }
}