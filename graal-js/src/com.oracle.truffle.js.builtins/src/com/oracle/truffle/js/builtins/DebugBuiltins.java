/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.builtins;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.utilities.JSONHelper;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugArrayTypeNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugAssertIntNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugClassNameNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugClassNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugCompileFunctionNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugContinueInInterpreterNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugCreateLargeIntegerNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugDumpCountersNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugDumpFunctionTreeNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugHeapDumpNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugInspectNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugIsHolesArrayNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugJSStackNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugLoadModuleNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugObjectSizeHistogramNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugObjectSizeNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugPrintNodeCountersNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugPrintNodeHistogramNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugPrintObjectNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugPrintSourceAttributionNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugShapeNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugStringCompareNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugSystemGCNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugToJavaStringNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugToLengthNodeGen;
import com.oracle.truffle.js.builtins.DebugBuiltinsFactory.DebugTypedArrayDetachBufferNodeGen;
import com.oracle.truffle.js.builtins.helper.ClassHistogramElement;
import com.oracle.truffle.js.builtins.helper.HeapDump;
import com.oracle.truffle.js.builtins.helper.ObjectSizeCalculator;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.NodeEvaluator;
import com.oracle.truffle.js.nodes.ScriptNode;
import com.oracle.truffle.js.nodes.cast.JSToLengthNode;
import com.oracle.truffle.js.nodes.cast.JSToObjectNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.runtime.AbstractJavaScriptLanguage;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSErrorType;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.LargeInteger;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSAbstractBuffer;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSArrayBuffer;
import com.oracle.truffle.js.runtime.builtins.JSDebug;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSGlobalObject;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.objects.JSModuleLoader;
import com.oracle.truffle.js.runtime.objects.JSModuleRecord;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.Pair;
import com.oracle.truffle.object.DynamicObjectImpl;

/**
 * Contains builtins for {@linkplain JSDebug}.
 */
public final class DebugBuiltins extends JSBuiltinsContainer.SwitchEnum<DebugBuiltins.Debug> {
    protected DebugBuiltins() {
        super(JSDebug.CLASS_NAME, Debug.class);
    }

    public enum Debug implements BuiltinEnum<Debug> {
        class_(1),
        getClass(1),
        className(1),
        shape(1),
        dumpCounters(0),
        dumpFunctionTree(1),
        compileFunction(2),
        inspect(2),
        printObject(1),
        toJavaString(1),
        srcattr(1),
        arraytype(1),
        assertInt(2),
        continueInInterpreter(0),
        stringCompare(2),
        isHolesArray(1),
        jsStack(0),
        loadModule(2),
        printNodeCounters(0),
        printNodeHistogram(0),
        createLargeInteger(1),
        typedArrayDetachBuffer(1),
        systemGC(0),

        objectSize(1) {
            @Override
            public boolean isAOTSupported() {
                return false;
            }
        },
        objectSizeHistogram(3) {
            @Override
            public boolean isAOTSupported() {
                return false;
            }
        },
        dumpHeap(2) {
            @Override
            public boolean isAOTSupported() {
                return false;
            }
        };

        private final int length;

        Debug(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, Debug builtinEnum) {
        switch (builtinEnum) {
            case class_:
                return DebugClassNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case getClass:
                return DebugClassNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case className:
                return DebugClassNameNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case shape:
                return DebugShapeNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case dumpCounters:
                return DebugDumpCountersNodeGen.create(context, builtin, args().createArgumentNodes(context));
            case dumpFunctionTree:
                return DebugDumpFunctionTreeNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case compileFunction:
                return DebugCompileFunctionNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case inspect:
                return DebugInspectNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case printObject:
                return DebugPrintObjectNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case toJavaString:
                return DebugToJavaStringNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case srcattr:
                return DebugPrintSourceAttributionNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case arraytype:
                return DebugArrayTypeNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case assertInt:
                return DebugAssertIntNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case continueInInterpreter:
                return DebugContinueInInterpreterNodeGen.create(context, builtin, false, args().createArgumentNodes(context));
            case stringCompare:
                return DebugStringCompareNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case isHolesArray:
                return DebugIsHolesArrayNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case jsStack:
                return DebugJSStackNodeGen.create(context, builtin, args().createArgumentNodes(context));
            case loadModule:
                return DebugLoadModuleNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));

            case systemGC:
                return DebugSystemGCNodeGen.create(context, builtin, args().createArgumentNodes(context));
            case printNodeCounters:
                return DebugPrintNodeCountersNodeGen.create(context, builtin, args().createArgumentNodes(context));
            case printNodeHistogram:
                return DebugPrintNodeHistogramNodeGen.create(context, builtin, args().createArgumentNodes(context));
            case typedArrayDetachBuffer:
                return DebugTypedArrayDetachBufferNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));

            case createLargeInteger:
                return DebugCreateLargeIntegerNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            default:
                if (!JSTruffleOptions.SubstrateVM) {
                    switch (builtinEnum) {
                        case objectSize:
                            return DebugObjectSizeNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
                        case objectSizeHistogram:
                            return DebugObjectSizeHistogramNodeGen.create(context, builtin, args().fixedArgs(3).createArgumentNodes(context));
                        case dumpHeap:
                            return DebugHeapDumpNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
                    }
                }
        }
        return null;
    }

    public abstract static class DebugContinueInInterpreter extends JSBuiltinNode {
        private final boolean invalidate;

        public DebugContinueInInterpreter(JSContext context, JSBuiltin builtin, boolean invalidate) {
            super(context, builtin);
            this.invalidate = invalidate;
        }

        @Specialization
        protected Object continueInInterpreter() {
            if (invalidate) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            } else {
                CompilerDirectives.transferToInterpreter();
            }
            return null;
        }
    }

    public abstract static class DebugClassNode extends JSBuiltinNode {
        private final boolean getName;

        public DebugClassNode(JSContext context, JSBuiltin builtin, boolean getName) {
            super(context, builtin);
            this.getName = getName;
        }

        @Specialization
        protected Object clazz(Object obj) {
            return obj == null ? null : getName ? obj.getClass().getName() : obj.getClass();
        }
    }

    public abstract static class DebugClassNameNode extends JSBuiltinNode {
        public DebugClassNameNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary
        protected static Object clazz(Object obj) {
            if (obj instanceof Symbol) {
                return Null.instance;
            } else if (JSObject.isJSObject(obj)) {
                DynamicObject jsObj = (DynamicObject) obj;
                if (jsObj.containsKey(JSRuntime.ITERATED_OBJECT_ID)) {
                    DynamicObject iteratedObj = (DynamicObject) jsObj.get(JSRuntime.ITERATED_OBJECT_ID);
                    return JSObject.getClassName(iteratedObj) + " Iterator";
                } else if (jsObj.containsKey(JSFunction.GENERATOR_STATE_ID)) {
                    return "Generator";
                } else if (JSProxy.isProxy(jsObj)) {
                    return clazz(JSProxy.getTarget(jsObj));
                }
                return JSObject.getClassName(jsObj);
            } else {
                return "not_an_object";
            }
        }
    }

    public abstract static class DebugShapeNode extends JSBuiltinNode {
        public DebugShapeNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object shape(Object obj) {
            if (JSObject.isDynamicObject(obj)) {
                return ((DynamicObject) obj).getShape().toString();
            }
            return Undefined.instance;
        }
    }

    public abstract static class DebugDumpCountersNode extends JSBuiltinNode {
        public DebugDumpCountersNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object dumpCounters() {
            com.oracle.truffle.object.DebugCounter.dumpCounters();
            com.oracle.truffle.js.runtime.util.DebugCounter.dumpCounters();
            return Undefined.instance;
        }
    }

    public abstract static class DebugDumpFunctionTreeNode extends JSBuiltinNode {
        public DebugDumpFunctionTreeNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization(guards = "isJSFunction(functionObj)")
        protected Object dumpFunctionTree(DynamicObject functionObj) {
            CallTarget target = JSFunction.getCallTarget(functionObj);
            if (target instanceof RootCallTarget) {
                NodeUtil.printTree(getContext().getWriter(), ((RootCallTarget) target).getRootNode());
            } else {
                getContext().getErrorWriter().println("Node tree node accessible.");
            }

            return Undefined.instance;
        }
    }

    public abstract static class DebugCompileFunctionNode extends JSBuiltinNode {
        private static final MethodHandle COMPILE_HANDLE;

        public DebugCompileFunctionNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static boolean compileFunction(Object fnObj) {
            if (!JSFunction.isJSFunction(fnObj)) {
                throw Errors.createTypeErrorNotAFunction(fnObj);
            }

            CallTarget callTarget = JSFunction.getCallTarget((DynamicObject) fnObj);

            if (COMPILE_HANDLE != null) {
                try {
                    COMPILE_HANDLE.invokeExact(callTarget);
                    return true;
                } catch (Throwable e) {
                }
            }

            return false;
        }

        static {
            MethodHandle compileHandle = null;
            if (Truffle.getRuntime().getName().contains("Graal")) {
                try {
                    Class<? extends CallTarget> optimizedCallTargetClass = Class.forName("com.oracle.graal.truffle.OptimizedCallTarget").asSubclass(CallTarget.class);
                    compileHandle = MethodHandles.lookup().findVirtual(optimizedCallTargetClass, "compile", MethodType.methodType(void.class)).asType(
                                    MethodType.methodType(void.class, CallTarget.class));
                } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException | SecurityException e) {
                }
            }
            COMPILE_HANDLE = compileHandle;
        }
    }

    public abstract static class DebugInspectNode extends JSBuiltinNode {
        public DebugInspectNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected Object inspect(DynamicObject object, Object level0) {
            int level = JSRuntime.toInt32(level0);
            getContext().getWriter().println(((DynamicObjectImpl) object).debugDump(level));
            return Undefined.instance;
        }
    }

    public abstract static class DebugPrintObjectNode extends JSBuiltinNode {
        public DebugPrintObjectNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected Object printObject(DynamicObject object, Object level0) {
            int level = level0 == Undefined.instance ? 1 : JSRuntime.toInt32(level0);
            getContext().getWriter().println(debugPrint(object, 0, level));
            return Undefined.instance;
        }

        @TruffleBoundary
        protected String debugPrint(DynamicObject object, int level, int levelStop) {
            List<String> properties = JSObject.enumerableOwnNames(object);
            StringBuilder sb = new StringBuilder(properties.size() * 10);
            sb.append("{\n");
            for (String key : properties) {
                indent(sb, level + 1);
                PropertyDescriptor desc = JSObject.getOwnProperty(object, key);

                // must not invoke accessor functions here
                sb.append(key);
                if (desc.isDataDescriptor()) {
                    Object value = JSObject.get(object, key);
                    if (JSObject.isDynamicObject(value)) {
                        if ((JSUserObject.isJSUserObject(value) || JSGlobalObject.isJSGlobalObject(value)) && !key.equals(JSObject.CONSTRUCTOR)) {
                            if (level < levelStop && !key.equals(JSObject.CONSTRUCTOR)) {
                                value = debugPrint((DynamicObject) value, level + 1, levelStop);
                            } else {
                                value = "{...}";
                            }
                        } else {
                            value = JSObject.getJSClass((DynamicObject) value);
                        }
                    }
                    sb.append(": ");
                    sb.append(value);
                }
                if (!key.equals(properties.get(properties.size() - 1))) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, level);
            sb.append('}');
            return sb.toString();
        }

        private static StringBuilder indent(StringBuilder sb, int level) {
            for (int i = 0; i < level; i++) {
                sb.append(' ');
            }
            return sb;
        }
    }

    public abstract static class DebugToJavaStringNode extends JSBuiltinNode {
        public DebugToJavaStringNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object toJavaString(Object thing) {
            return String.valueOf(thing);
        }
    }

    public abstract static class DebugPrintSourceAttribution extends JSBuiltinNode {
        public DebugPrintSourceAttribution(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization(guards = "isJSFunction(function)")
        protected Object printSourceAttribution(DynamicObject function) {
            CallTarget callTarget = JSFunction.getCallTarget(function);
            if (callTarget instanceof RootCallTarget) {
                return NodeUtil.printSourceAttributionTree(((RootCallTarget) callTarget).getRootNode());
            }
            return Undefined.instance;
        }

        @TruffleBoundary
        @Specialization
        protected Object printSourceAttribution(String code) {
            ScriptNode scriptNode = ((NodeEvaluator) getContext().getEvaluator()).evalCompile(getContext(), code, "<eval>");
            return NodeUtil.printSourceAttributionTree(scriptNode.getRootNode());
        }
    }

    public abstract static class DebugArrayTypeNode extends JSBuiltinNode {
        public DebugArrayTypeNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected Object arraytype(Object array) {
            if (!(JSObject.isDynamicObject(array)) || !(JSObject.hasArray((DynamicObject) array))) {
                return "NOT_AN_ARRAY";
            }
            return JSObject.getArray((DynamicObject) array).getClass().getSimpleName();
        }
    }

    public abstract static class DebugAssertIntNode extends JSBuiltinNode {
        public DebugAssertIntNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected Object assertInt(Object value, Object message) {
            if (!(value instanceof Integer)) {
                throw Errors.createTypeError("assert: expected integer here, got " + value.getClass().getSimpleName() + ", message: " + JSRuntime.toString(message));
            }
            return Undefined.instance;
        }
    }

    public abstract static class DebugObjectSizeNode extends JSBuiltinNode {
        public DebugObjectSizeNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected double objectSize(Object object) {
            if (object == null) {
                return Double.NaN;
            }
            return new ObjectSizeCalculator(ObjectSizeCalculator.CurrentLayout.SPEC, defaultPredicate()).calculateObjectSize(object);
        }

        private static Predicate<Object> defaultPredicate() {
            return o -> !(o instanceof Reference || o instanceof Class<?> || o instanceof TruffleRuntime || o instanceof ClassValue<?>);
        }
    }

    public abstract static class DebugObjectSizeHistogramNode extends JSBuiltinNode {
        public DebugObjectSizeHistogramNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected Object objectSizeHistogram(Object object, Object limit0, Object pred0) {
            if (object == null) {
                return Undefined.instance;
            }

            Predicate<Object> predicate = DebugObjectSizeNode.defaultPredicate();
            if (JSFunction.isJSFunction(pred0)) {
                predicate = predicate.and(o -> Boolean.TRUE.equals(JSFunction.call((DynamicObject) pred0, Undefined.instance, new Object[]{o})));
            }

            ObjectSizeCalculator osc = new ObjectSizeCalculator(ObjectSizeCalculator.CurrentLayout.SPEC, predicate);
            final long totalSize = osc.calculateObjectSize(object);
            final List<ClassHistogramElement> list = osc.getClassHistogram();

            final StringBuilder sb = new StringBuilder();
            Collections.sort(list, (o1, o2) -> Long.compare(o2.getBytes(), o1.getBytes()));

            int limit = JSRuntime.toInt32(limit0);
            limit = limit == 0 ? 20 : limit;

            final int maxClassNameLength = 100;
            String formatString = "%-" + maxClassNameLength + "s %10d bytes (%8d instances)";
            int totalInstances = 0;
            for (final ClassHistogramElement e : list) {
                totalInstances += e.getInstances();
            }
            sb.append(String.format(formatString, "Total Size", totalSize, totalInstances)).append('\n');
            for (int i = 0; i < maxClassNameLength + 38; i++) {
                sb.append('-');
            }
            sb.append('\n');
            for (final ClassHistogramElement e : list) {
                String className = e.getClazz().getName().replaceFirst("^(.+)(...)(.{" + (maxClassNameLength - 3) + "})$", "...$3");
                final String line = String.format(formatString, className, e.getBytes(), e.getInstances());
                sb.append(line).append('\n');
                if (--limit == 0) {
                    sb.append("...").append('\n');
                    break;
                }
            }

            return sb.toString();
        }
    }

    public abstract static class DebugHeapDumpNode extends JSBuiltinNode {
        public DebugHeapDumpNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected Object heapDump(Object fileName0, Object live0) {
            String fileName = fileName0 == Undefined.instance ? HeapDump.defaultDumpName() : JSRuntime.toString(fileName0);
            boolean live = live0 == Undefined.instance ? true : JSRuntime.toBoolean(live0);
            try {
                HeapDump.dump(fileName, live);
            } catch (IOException e) {
                throw JSException.create(JSErrorType.Error, e.getMessage(), e, this);
            }

            return Undefined.instance;
        }
    }

    /**
     * Used by testV8!
     */
    public abstract static class DebugStringCompareNode extends JSBuiltinNode {

        public DebugStringCompareNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected int stringCompare(Object a, Object b) {
            String str1 = JSRuntime.toString(a);
            String str2 = JSRuntime.toString(b);
            int result = str1.compareTo(str2);
            if (result == 0) {
                return 0;
            } else if (result < 0) {
                return -1;
            } else {
                return 1;
            }
        }
    }

    /**
     * Exposes the "holes" property of arrays. Used e.g. by V8HasFastHoleyElements.
     */
    public abstract static class DebugIsHolesArrayNode extends JSBuiltinNode {
        @Child private JSToObjectNode toObjectNode;
        private final ValueProfile arrayType;
        private final ConditionProfile isArray;

        public DebugIsHolesArrayNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.toObjectNode = JSToObjectNode.createToObject(getContext());
            this.arrayType = ValueProfile.createClassProfile();
            this.isArray = ConditionProfile.createBinaryProfile();
        }

        public abstract boolean executeBoolean(Object object);

        @Specialization
        protected boolean isHolesArray(Object arr) {
            TruffleObject obj = toObjectNode.executeTruffleObject(arr);
            if (isArray.profile(JSArray.isJSArray(obj))) {
                DynamicObject dynObj = (DynamicObject) obj;
                return arrayType.profile(JSObject.getArray(dynObj)).hasHoles(dynObj);
            } else {
                return false;
            }
        }

        @Override
        protected JavaScriptNode copyUninitialized() {
            return DebugIsHolesArrayNodeGen.create(getContext(), getBuiltin(), cloneUninitialized(getArguments()));
        }
    }

    /**
     * Prints the current JS stack.
     */
    public abstract static class DebugJSStackNode extends JSBuiltinNode {

        public DebugJSStackNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object printJSStack() {
            JSException.printJSStackTrace(getParent());
            return Undefined.instance;
        }
    }

    public abstract static class DebugLoadModuleNode extends JSBuiltinNode {

        public DebugLoadModuleNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected String loadModule(Object nameObj, Object modulesSourceMapObj) {
            String name = JSRuntime.toString(nameObj);
            DynamicObject modulesSourceMap = (DynamicObject) modulesSourceMapObj;
            JSContext context = getContext();
            Evaluator evaluator = context.getEvaluator();
            JSModuleLoader moduleLoader = new JSModuleLoader() {
                private final Map<String, JSModuleRecord> moduleMap = new HashMap<>();

                private Source resolveModuleSource(@SuppressWarnings("unused") JSModuleRecord referencingModule, String specifier) {
                    Object moduleEntry = JSObject.get(modulesSourceMap, specifier);
                    if (moduleEntry == Undefined.instance) {
                        throw Errors.createSyntaxError(String.format("Could not find imported module %s", specifier));
                    }
                    String code = JSRuntime.toString(moduleEntry);
                    return Source.newBuilder(code).name(name).mimeType(AbstractJavaScriptLanguage.APPLICATION_MIME_TYPE).build();
                }

                @Override
                public JSModuleRecord resolveImportedModule(JSModuleRecord referencingModule, String specifier) {
                    return moduleMap.computeIfAbsent(specifier, (key) -> evaluator.parseModule(context, resolveModuleSource(referencingModule, key), this));
                }

                @Override
                public JSModuleRecord loadModule(Source moduleSource) {
                    throw new UnsupportedOperationException();
                }
            };
            JSModuleRecord module = moduleLoader.resolveImportedModule(null, name);
            evaluator.moduleDeclarationInstantiation(module);
            evaluator.moduleEvaluation(context.getRealm(), module);
            return String.valueOf(module);
        }
    }

    public abstract static class DebugPrintNodeCountersNode extends JSBuiltinNode {
        public DebugPrintNodeCountersNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object printNodeCounters() {
            String s = JSONHelper.getResult();
            System.out.println("NodeCreateCount: " + countInString(s, "\"createNode\""));
            System.out.println("NodeReplaceCount: " + countInString(s, "\"replaceNode\""));
            return Undefined.instance;
        }

        private static long countInString(String string, String pattern) {
            int pos = 0;
            int count = 0;
            while ((pos = string.indexOf(pattern, pos + 1)) >= 0) {
                count++;
            }
            return count;
        }
    }

    public abstract static class DebugSystemGCNode extends JSBuiltinNode {
        public DebugSystemGCNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object systemGC() {
            System.gc();
            return Undefined.instance;
        }
    }

    public abstract static class DebugPrintNodeHistogramNode extends JSBuiltinNode {
        public DebugPrintNodeHistogramNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object printNodeHistogram() {
            String s = JSONHelper.getResult();
            List<Pair<String, Integer>> list = calculateHistogram(s);
            list.sort(new Comparator<Pair<String, Integer>>() {
                @Override
                public int compare(Pair<String, Integer> p1, Pair<String, Integer> p2) {
                    return p1.getSecond().compareTo(p2.getSecond());
                }
            });
            int sum = 0;
            for (Pair<String, Integer> node : list) {
                int value = node.getSecond();
                sum += value;
                System.out.println(node.getFirst() + ";" + value + ";");
            }
            System.out.println("TOTAL_NODE_COUNT;" + sum + ";");
            return Undefined.instance;
        }

        private static List<Pair<String, Integer>> calculateHistogram(String s) {
            int pos = 0;
            String pattern = "\"type\"";
            HashMap<String, Integer> map = new HashMap<>();
            while ((pos = s.indexOf(pattern, pos + 1)) >= 0) {
                int startQuote = s.indexOf("\"", pos + pattern.length() + 1);
                int endQuote = s.indexOf("\"", startQuote + 1);
                inc(map, s.substring(startQuote + 1, endQuote));
            }
            List<Pair<String, Integer>> list = new ArrayList<>(map.size());
            for (String key : map.keySet()) {
                list.add(new Pair<>(key, map.get(key)));
            }
            return list;
        }

        private static void inc(HashMap<String, Integer> map, String name) {
            int value = map.containsKey(name) ? map.get(name) : 0;
            map.put(name, value + 1);
        }
    }

    public abstract static class DebugTypedArrayDetachBufferNode extends JSBuiltinNode {
        public DebugTypedArrayDetachBufferNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected static Object detachBuffer(Object obj) {
            if (!(JSAbstractBuffer.isJSAbstractBuffer(obj))) {
                throw Errors.createTypeError("Buffer expected");
            }
            JSArrayBuffer.detachArrayBuffer((DynamicObject) obj);
            return Undefined.instance;
        }
    }

    public abstract static class DebugCreateLargeInteger extends JSBuiltinNode {

        public DebugCreateLargeInteger(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected LargeInteger createLargeInteger(int a) {
            return LargeInteger.valueOf(a);
        }

        @Specialization
        protected LargeInteger createLargeInteger(Object a) {
            return LargeInteger.valueOf(JSRuntime.toInt32(a));
        }
    }

    /**
     * Calls [[ToLength]], used by V8mockup.js and internal js files.
     */
    public abstract static class DebugToLengthNode extends JSBuiltinNode {
        @Child private JSToLengthNode toLengthNode;

        public DebugToLengthNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            toLengthNode = JSToLengthNode.create();
        }

        @Specialization
        protected Object toLengthOp(Object obj) {
            long value = toLengthNode.executeLong(obj);
            double d = value;
            if (JSRuntime.doubleIsRepresentableAsInt(d)) {
                return (int) d;
            } else {
                return d;
            }
        }

        @Override
        protected JavaScriptNode copyUninitialized() {
            return DebugToLengthNodeGen.create(getContext(), getBuiltin(), cloneUninitialized(getArguments()));
        }
    }
}