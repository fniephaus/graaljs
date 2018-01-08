/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.function;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.nodes.NodeFactory;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.Builtin;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.objects.JSAttributes;

public final class JSBuiltin implements Builtin, JSFunctionData.CallTargetInitializer {
    private final String name;
    private final String fullName;
    private final Object key;
    private final int length;
    private final byte attributeFlags;
    private final byte ecmaScriptVersion;
    private final boolean annexB;

    private final BuiltinNodeFactory functionNodeFactory;
    private final BuiltinNodeFactory constructorNodeFactory;
    private final BuiltinNodeFactory newTargetConstructorNodeFactory;

    public JSBuiltin(String containerName, Object key, int length, int attributeFlags, int ecmaScriptVersion, boolean annexB,
                    BuiltinNodeFactory functionNodeFactory, BuiltinNodeFactory constructorNodeFactory, BuiltinNodeFactory newTargetConstructorFactory) {
        assert key instanceof Symbol || (key instanceof String && !((String) key).isEmpty() && !((String) key).endsWith("_") && !((String) key).startsWith("_"));
        assert (byte) ecmaScriptVersion == ecmaScriptVersion && (byte) attributeFlags == attributeFlags;
        this.name = key instanceof Symbol ? ((Symbol) key).toFunctionNameString() : (String) key;
        this.fullName = (containerName == null) ? name : (containerName + "." + name);
        this.key = key;
        this.length = length;
        this.ecmaScriptVersion = (byte) ecmaScriptVersion;
        this.attributeFlags = (byte) attributeFlags;
        this.annexB = annexB;
        this.functionNodeFactory = functionNodeFactory;
        this.constructorNodeFactory = constructorNodeFactory;
        this.newTargetConstructorNodeFactory = newTargetConstructorFactory;
    }

    public JSBuiltin(String containerName, String name, int length, int flags, BuiltinNodeFactory functionNodeFactory) {
        this(containerName, name, length, flags, 5, false, functionNodeFactory, null, null);
    }

    /**
     * Returns the simple name of the built-in. A simple name is the name of the corresponding
     * function, i.e., {@code sort} for {@code Array.prototype.sort} built-in.
     *
     * @return simple name of the built-in.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Returns the "fully-qualified" name of the built-in. The "fully-qualified" name contains also
     * the name of the owner/holder of the built-in ({@code Array.prototype.sort}, for example).
     *
     * @return "fully-qualified" name of the built-in.
     */
    public String getFullName() {
        return fullName;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public int getLength() {
        return length;
    }

    public boolean isConstructor() {
        return constructorNodeFactory != null;
    }

    public boolean hasSeparateConstructor() {
        return isConstructor() && constructorNodeFactory != functionNodeFactory;
    }

    public boolean hasNewTargetConstructor() {
        return isConstructor() && newTargetConstructorNodeFactory != null;
    }

    @Override
    public int getECMAScriptVersion() {
        return Math.max(5, ecmaScriptVersion);
    }

    @Override
    public boolean isAnnexB() {
        return annexB;
    }

    @Override
    public int getAttributeFlags() {
        return attributeFlags;
    }

    @Override
    public boolean isConfigurable() {
        return (attributeFlags & JSAttributes.NOT_CONFIGURABLE) == 0;
    }

    @Override
    public boolean isWritable() {
        return (attributeFlags & JSAttributes.NOT_WRITABLE) == 0;
    }

    @Override
    public boolean isEnumerable() {
        return (attributeFlags & JSAttributes.NOT_ENUMERABLE) == 0;
    }

    public SourceSection getSourceSection() {
        return createSourceSection(name);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "JSBuiltin [name=" + name + ", length=" + length + "]";
    }

    @Override
    public JSFunctionData createFunctionData(JSContext context) {
        JSFunctionData functionData = JSFunctionData.create(context, getLength(), getName(), isConstructor(), false, false, true);

        if (JSTruffleOptions.LazyFunctionData) {
            functionData.setLazyInit(this);
        } else {
            initializeEager(functionData);
        }

        return functionData;
    }

    JSBuiltinNode createNode(JSContext context, boolean construct, boolean newTarget) {
        JSBuiltinNode builtinNode = createNodeImpl(context, construct, newTarget);
        builtinNode.construct = construct;
        builtinNode.newTarget = newTarget;
        return builtinNode;
    }

    private JSBuiltinNode createNodeImpl(JSContext context, boolean construct, boolean newTarget) {
        if (newTarget && newTargetConstructorNodeFactory != null) {
            return newTargetConstructorNodeFactory.createNode(context, this);
        } else if (construct && constructorNodeFactory != null) {
            return constructorNodeFactory.createNode(context, this);
        } else {
            return functionNodeFactory.createNode(context, this);
        }
    }

    public static SourceSection createSourceSection(@SuppressWarnings("unused") String name) {
        return JSFunction.BUILTIN_SOURCE_SECTION;
    }

    private static void initializeFunctionData(JSFunctionData functionData, JSBuiltin builtin) {
        JSContext context = functionData.getContext();
        JSBuiltinNode functionRoot = JSBuiltinNode.createBuiltin(context, builtin, false, false);
        FrameDescriptor frameDescriptor = null;
        FunctionRootNode callRoot = FunctionRootNode.create(functionRoot, frameDescriptor, functionData, builtin.getSourceSection(), builtin.getFullName());

        CallTarget callTarget = Truffle.getRuntime().createCallTarget(callRoot);
        callTarget = functionData.setRootTarget(callTarget);
        functionData.setCallTarget(callTarget);
    }

    private static void initializeFunctionDataCallTarget(JSFunctionData functionData, JSBuiltin builtin, JSFunctionData.Target target, CallTarget callTarget) {
        JSContext context = functionData.getContext();
        NodeFactory factory = NodeFactory.getDefaultInstance();
        FrameDescriptor frameDescriptor = null;
        if (target == JSFunctionData.Target.Construct) {
            RootNode constructRoot;
            if (builtin.hasSeparateConstructor()) {
                JSBuiltinNode constructNode = JSBuiltinNode.createBuiltin(context, builtin, true, false);
                constructRoot = FunctionRootNode.create(constructNode, frameDescriptor, functionData, builtin.getSourceSection(), builtin.getFullName());
            } else {
                constructRoot = factory.createConstructorRootNode(functionData, callTarget, false);
            }
            CallTarget constructTarget;
            constructTarget = Truffle.getRuntime().createCallTarget(constructRoot);
            constructTarget = functionData.setConstructTarget(constructTarget);
        } else if (target == JSFunctionData.Target.ConstructNewTarget) {
            JavaScriptRootNode constructNewTargetRoot;
            if (builtin.hasNewTargetConstructor()) {
                AbstractBodyNode constructNewTargetNode = JSBuiltinNode.createBuiltin(context, builtin, true, true);
                constructNewTargetRoot = FunctionRootNode.create(constructNewTargetNode, frameDescriptor, functionData, builtin.getSourceSection(), builtin.getFullName());
            } else {
                CallTarget constructTarget = functionData.getConstructTarget();
                constructNewTargetRoot = factory.createDropNewTarget(constructTarget);
            }
            functionData.setConstructNewTarget(Truffle.getRuntime().createCallTarget(constructNewTargetRoot));
        }
    }

    @Override
    public void initializeRoot(JSFunctionData functionData) {
        initializeFunctionData(functionData, this);
    }

    @Override
    public void initializeCallTarget(JSFunctionData functionData, JSFunctionData.Target target, CallTarget callTarget) {
        initializeFunctionDataCallTarget(functionData, this, target, callTarget);
    }
}