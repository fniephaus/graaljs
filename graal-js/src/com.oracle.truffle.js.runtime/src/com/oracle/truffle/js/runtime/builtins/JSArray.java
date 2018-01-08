/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.array.ArrayAllocationSite;
import com.oracle.truffle.js.runtime.array.ScriptArray;
import com.oracle.truffle.js.runtime.array.SparseArray;
import com.oracle.truffle.js.runtime.array.dyn.ConstantByteArray;
import com.oracle.truffle.js.runtime.array.dyn.ConstantDoubleArray;
import com.oracle.truffle.js.runtime.array.dyn.ConstantEmptyPrototypeArray;
import com.oracle.truffle.js.runtime.array.dyn.ConstantIntArray;
import com.oracle.truffle.js.runtime.array.dyn.ConstantObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.HolesObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.LazyRegexResultArray;
import com.oracle.truffle.js.runtime.array.dyn.ZeroBasedDoubleArray;
import com.oracle.truffle.js.runtime.array.dyn.ZeroBasedIntArray;
import com.oracle.truffle.js.runtime.array.dyn.ZeroBasedJSObjectArray;
import com.oracle.truffle.js.runtime.array.dyn.ZeroBasedObjectArray;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.PropertyProxy;

import static com.oracle.truffle.js.runtime.objects.JSObjectUtil.putConstructorProperty;
import static com.oracle.truffle.js.runtime.objects.JSObjectUtil.putFunctionsFromContainer;
import static com.oracle.truffle.js.runtime.objects.JSObjectUtil.putProxyProperty;

public final class JSArray extends JSAbstractArray implements JSConstructorFactory.Default.WithFunctionsAndSpecies {

    public static final String CLASS_NAME = "Array";
    public static final String PROTOTYPE_NAME = "Array.prototype";

    public static final JSArray INSTANCE = new JSArray();

    private JSArray() {
    }

    public static DynamicObject createConstant(JSContext context, Object[] elements) {
        return create(context, ScriptArray.createConstantArray(elements), elements, elements.length);
    }

    public static DynamicObject createEmpty(JSContext context, int length) {
        if (length < 0) {
            throw Errors.createRangeErrorInvalidArrayLength();
        }
        return createEmptyChecked(context, length);
    }

    /**
     * Creates an empty array of a certain size. The size is expected to be within the valid range
     * of JavaScript array sizes.
     */
    private static DynamicObject createEmptyChecked(JSContext context, int length) {
        return createConstantEmptyArray(context, length);
    }

    public static DynamicObject createEmpty(JSContext context, long length) {
        if (!JSRuntime.isValidArrayLength(length)) {
            throw Errors.createRangeErrorInvalidArrayLength();
        } else if (length > Integer.MAX_VALUE) {
            return createSparseArray(context, length);
        }
        return createEmptyChecked(context, length);
    }

    /**
     * Creates an empty array of a certain size. The size is expected to be within the valid range
     * of JavaScript array sizes.
     */
    public static DynamicObject createEmptyChecked(JSContext context, long length) {
        assert 0 <= length && length <= Integer.MAX_VALUE;
        return createConstantEmptyArray(context, (int) length);
    }

    public static DynamicObject createEmptyZeroLength(JSContext context) {
        return createConstantEmptyArray(context);
    }

    public static DynamicObject create(JSContext context, ScriptArray arrayType, Object array, long length) {
        return create(context, arrayType, array, length, 0);
    }

    public static DynamicObject create(JSContext context, ScriptArray arrayType, Object array, long length, int usedLength) {
        return create(context, arrayType, array, length, usedLength, 0, 0);
    }

    public static DynamicObject create(JSContext context, ScriptArray arrayType, Object array, long length, int usedLength, int indexOffset, int arrayOffset) {
        return create(context, arrayType, array, length, usedLength, indexOffset, arrayOffset, 0);
    }

    public static DynamicObject create(JSContext context, ScriptArray arrayType, Object array, long length, int usedLength, int indexOffset, int arrayOffset, int holeCount) {
        return create(context, context.getArrayFactory(), arrayType, array, null, length, usedLength, indexOffset, arrayOffset, holeCount);
    }

    public static DynamicObject create(JSContext context, DynamicObjectFactory factory, ScriptArray arrayType, Object array, ArrayAllocationSite site, long length, int usedLength, int indexOffset,
                    int arrayOffset, int holeCount) {
        // (array, arrayType, allocSite, length, usedLength, indexOffset, arrayOffset, holeCount)
        assert JSRuntime.isRepresentableAsUnsignedInt(length);
        DynamicObject obj = JSObject.create(context, factory, array, arrayType, site, (int) length, usedLength, indexOffset, arrayOffset, holeCount);
        assert isJSArray(obj);
        return obj;
    }

    public static boolean isJSArray(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSArray((DynamicObject) obj);
    }

    public static boolean isJSArray(DynamicObject obj) {
        return isInstance(obj, INSTANCE) || isInstance(obj, JSSlowArray.INSTANCE);
    }

    public static boolean isJSFastArray(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSFastArray((DynamicObject) obj);
    }

    public static boolean isJSFastArray(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return getClassName();
    }

    @Override
    public DynamicObject createPrototype(JSRealm realm, DynamicObject ctor) {
        JSContext ctx = realm.getContext();
        DynamicObject arrayPrototype = JSObject.create(realm, realm.getObjectPrototype(), INSTANCE);

        putArrayProperties(arrayPrototype, ConstantEmptyPrototypeArray.createConstantEmptyPrototypeArray());

        putConstructorProperty(ctx, arrayPrototype, ctor);
        putFunctionsFromContainer(realm, arrayPrototype, PROTOTYPE_NAME);
        // sets the length just for the prototype
        putProxyProperty(arrayPrototype, makeArrayLengthProxyProperty());
        return arrayPrototype;
    }

    public static Shape makeInitialArrayShape(JSContext context, DynamicObject prototype) {
        assert JSShape.getProtoChildTree(prototype.getShape(), INSTANCE) == null;
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, INSTANCE, context);
        initialShape = addArrayProperties(initialShape);
        initialShape = initialShape.addProperty(makeArrayLengthProxyProperty());
        return initialShape;
    }

    public static Property makeArrayLengthProxyProperty() {
        return JSObjectUtil.makeProxyProperty(LENGTH, new ArrayLengthProxyProperty(), JSAttributes.notConfigurableNotEnumerableWritable());
    }

    public static JSConstructor createConstructor(JSRealm realm) {
        return INSTANCE.createConstructorAndPrototype(realm);
    }

    public static class ArrayLengthProxyProperty implements PropertyProxy {
        @Override
        public Object get(DynamicObject store) {
            assert isJSArray(store);
            long length = JSArray.INSTANCE.getLength(store);
            return (double) length;
        }

        @Override
        public boolean set(DynamicObject store, Object value) {
            assert isJSArray(store);
            return JSArray.setLength(store, value);
        }
    }

    @TruffleBoundary
    static boolean setLength(DynamicObject store, Object value) {
        long arrLength = 0;
        if (value instanceof Integer && (int) value >= 0) {
            arrLength = (int) value;
        } else {
            arrLength = toArrayIndexOrRangeError(value);
        }

        if (arrLength >= 0) {
            return ((JSAbstractArray) JSObject.getJSClass(store)).setLength(store, arrLength, false);
        }
        return true;
    }

    public static DynamicObject createConstantEmptyArray(JSContext context, int capacity) {
        ScriptArray arrayType = ScriptArray.createConstantEmptyArray();
        return create(context, arrayType, ScriptArray.EMPTY_OBJECT_ARRAY, capacity);
    }

    public static DynamicObject createConstantEmptyArray(JSContext context) {
        return createConstantEmptyArray(context, 0);
    }

    public static DynamicObject createConstantEmptyArray(JSContext context, ArrayAllocationSite site) {
        return createConstantEmptyArray(context, site, 0);
    }

    public static DynamicObject createConstantEmptyArray(JSContext context, ArrayAllocationSite site, int capacity) {
        ScriptArray arrayType = ScriptArray.createConstantEmptyArray();
        return create(context, context.getArrayFactory(), arrayType, ScriptArray.EMPTY_OBJECT_ARRAY, site, capacity, 0, 0, 0, 0);
    }

    public static DynamicObject createConstantByteArray(JSContext context, byte[] byteArray) {
        ScriptArray arrayType = ConstantByteArray.createConstantByteArray();
        return create(context, arrayType, byteArray, byteArray.length);
    }

    public static DynamicObject createConstantIntArray(JSContext context, int[] intArray) {
        ScriptArray arrayType = ConstantIntArray.createConstantIntArray();
        return create(context, arrayType, intArray, intArray.length);
    }

    public static DynamicObject createConstantDoubleArray(JSContext context, double[] doubleArray) {
        ScriptArray arrayType = ConstantDoubleArray.createConstantDoubleArray();
        return create(context, arrayType, doubleArray, doubleArray.length);
    }

    public static DynamicObject createConstantObjectArray(JSContext context, Object[] objectArray) {
        ScriptArray arrayType = ConstantObjectArray.createConstantObjectArray();
        return create(context, arrayType, objectArray, objectArray.length);
    }

    public static DynamicObject createZeroBasedHolesObjectArray(JSContext context, Object[] objectArray, int usedLength, int arrayOffset, int holeCount) {
        return create(context, HolesObjectArray.createHolesObjectArray(), objectArray, objectArray.length, usedLength, 0, arrayOffset, holeCount);
    }

    public static DynamicObject createZeroBasedIntArray(JSContext context, int[] intArray) {
        return create(context, ZeroBasedIntArray.createZeroBasedIntArray(), intArray, intArray.length, intArray.length, 0, 0);
    }

    public static DynamicObject createZeroBasedDoubleArray(JSContext context, double[] doubleArray) {
        return create(context, ZeroBasedDoubleArray.createZeroBasedDoubleArray(), doubleArray, doubleArray.length, doubleArray.length, 0, 0);
    }

    public static DynamicObject createZeroBasedObjectArray(JSContext context, Object[] objectArray) {
        return create(context, ZeroBasedObjectArray.createZeroBasedObjectArray(), objectArray, objectArray.length, objectArray.length, 0, 0);
    }

    public static DynamicObject createZeroBasedJSObjectArray(JSContext context, DynamicObject[] objectArray) {
        return create(context, ZeroBasedJSObjectArray.createZeroBasedJSObjectArray(), objectArray, objectArray.length, objectArray.length, 0, 0);
    }

    public static DynamicObject createSparseArray(JSContext context, long length) {
        return create(context, SparseArray.createSparseArray(), SparseArray.createArrayMap(), length);
    }

    public static DynamicObject createLazyRegexArray(JSContext context, int length, TruffleObject regexResult, String input) {
        assert JSRuntime.isRepresentableAsUnsignedInt(length);
        DynamicObjectFactory factory = context.getRealm().getLazyRegexArrayFactory();
        ScriptArray arrayType = LazyRegexResultArray.createLazyRegexResultArray();
        Object array = ScriptArray.EMPTY_OBJECT_ARRAY;
        ArrayAllocationSite site = null;
        int usedLength = 0;
        int indexOffset = 0;
        int arrayOffset = 0;
        int holeCount = 0;
        DynamicObject obj = JSObject.create(context, factory, array, arrayType, site, length, usedLength, indexOffset, arrayOffset, holeCount, regexResult, input);
        assert isJSArray(obj);
        return obj;
    }
}