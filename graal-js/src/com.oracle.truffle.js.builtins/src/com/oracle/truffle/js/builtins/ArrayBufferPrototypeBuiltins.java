/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.builtins;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.js.builtins.ArrayBufferPrototypeBuiltinsFactory.JSArrayBufferSliceNodeGen;
import com.oracle.truffle.js.builtins.ArrayPrototypeBuiltins.ArraySpeciesConstructorNode;
import com.oracle.truffle.js.nodes.cast.JSToIntegerSpecialNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.builtins.JSArrayBuffer;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * Contains builtins for {@linkplain JSArrayBuffer}.prototype.
 */
public final class ArrayBufferPrototypeBuiltins extends JSBuiltinsContainer.Lambda {
    public ArrayBufferPrototypeBuiltins() {
        super(JSArrayBuffer.PROTOTYPE_NAME);
        defineFunction("slice", 2, (context, builtin) -> JSArrayBufferSliceNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context)));
    }

    public abstract static class JSArrayBufferOperation extends JSBuiltinNode {

        public JSArrayBufferOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToIntegerSpecialNode toIntegerNode;

        protected long toInteger(Object thisObject) {
            if (toIntegerNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toIntegerNode = insert(JSToIntegerSpecialNode.create());
            }
            return toIntegerNode.executeLong(thisObject);
        }
    }

    public abstract static class JSArrayBufferAbstractSliceNode extends JSArrayBufferOperation {

        @Child private ArraySpeciesConstructorNode arraySpeciesCreateNode;

        public JSArrayBufferAbstractSliceNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected int getStart(Object start, int len) {
            long relativeStart = toInteger(start);
            if (relativeStart < 0) {
                return (int) Math.max((len + relativeStart), 0);
            } else {
                return (int) Math.min(relativeStart, len);
            }
        }

        protected int getEnd(Object end, int len) {
            long relativeEnd = end == Undefined.instance ? len : toInteger(end);
            if (relativeEnd < 0) {
                return (int) Math.max((len + relativeEnd), 0);
            } else {
                return (int) Math.min(relativeEnd, len);
            }
        }

        /**
         * Clamp index to range [lowerBound,upperBound]. A negative index refers from upperBound.
         */
        protected static int clampIndex(int index, int lowerBound, int upperBound) {
            return clamp(index >= 0 ? index : index + upperBound, lowerBound, upperBound);
        }

        /**
         * Clamp index to range [lowerBound,upperBound].
         */
        private static int clamp(int index, int lowerBound, int upperBound) {
            return Math.max(Math.min(index, upperBound), lowerBound);
        }

        public ArraySpeciesConstructorNode getArraySpeciesConstructorNode() {
            if (arraySpeciesCreateNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                arraySpeciesCreateNode = insert(ArraySpeciesConstructorNode.create(getContext(), true));
            }
            return arraySpeciesCreateNode;
        }

        @TruffleBoundary
        protected static void sliceDirectIntl(ByteBuffer byteBuffer, int clampedBegin, int clampedEnd, ByteBuffer resBuffer) {
            resBuffer.put(((ByteBuffer) byteBuffer.duplicate().position(clampedBegin).limit(clampedEnd)).order(ByteOrder.nativeOrder()));
        }

    }

    public abstract static class JSArrayBufferSliceNode extends JSArrayBufferAbstractSliceNode {

        private final BranchProfile errorBranch = BranchProfile.create();

        public JSArrayBufferSliceNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        /**
         * ArrayBuffer slice(long begin, optional long end).
         *
         * Returns a new ArrayBuffer whose contents are a copy of this ArrayBuffer's bytes from
         * begin, inclusive, up to end, exclusive. If either begin or end is negative, it refers to
         * an index from the end of the array, as opposed to from the beginning.
         *
         * If end is unspecified, the new ArrayBuffer contains all bytes from begin to the end of
         * this ArrayBuffer.
         *
         * The range specified by the begin and end values is clamped to the valid index range for
         * the current array. If the computed length of the new ArrayBuffer would be negative, it is
         * clamped to zero.
         *
         * @param thisObj ArrayBuffer
         * @param begin begin index
         * @param end end index
         * @return sliced ArrayBuffer
         */
        @Specialization(guards = "isJSHeapArrayBuffer(thisObj)")
        protected DynamicObject slice(DynamicObject thisObj, int begin, int end) {
            byte[] byteArray = JSArrayBuffer.getByteArray(thisObj);
            int clampedBegin = clampIndex(begin, 0, byteArray.length);
            int clampedEnd = clampIndex(end, clampedBegin, byteArray.length);
            int newLen = Math.max(clampedEnd - clampedBegin, 0);

            DynamicObject resObj = constructNewArrayBuffer(thisObj, newLen);
            checkErrors(resObj, thisObj, newLen, false);

            byte[] newByteArray = JSArrayBuffer.getByteArray(resObj);
            System.arraycopy(byteArray, clampedBegin, newByteArray, 0, newLen);
            return resObj;
        }

        private DynamicObject constructNewArrayBuffer(DynamicObject thisObj, int newLen) {
            DynamicObject defaultConstructor = getContext().getRealm().getArrayBufferConstructor().getFunctionObject();
            DynamicObject constr = getArraySpeciesConstructorNode().speciesConstructor(thisObj, defaultConstructor);
            return (DynamicObject) getArraySpeciesConstructorNode().construct(constr, newLen);
        }

        private void checkErrors(DynamicObject resObj, DynamicObject thisObj, int newLen, boolean direct) {
            if ((direct && !JSArrayBuffer.isJSDirectArrayBuffer(resObj)) || (!direct && !JSArrayBuffer.isJSHeapArrayBuffer(resObj))) {
                errorBranch.enter();
                throw Errors.createTypeErrorArrayBufferExpected();
            }
            if (!getContext().getTypedArrayNotDetachedAssumption().isValid() && JSArrayBuffer.isDetachedBuffer(resObj)) {
                errorBranch.enter();
                throw Errors.createTypeError("cannot slice detached buffer");
            }
            if (resObj == thisObj) {
                errorBranch.enter();
                throw Errors.createTypeError("SameValue(new, O) is forbidden");
            }
            if ((direct && JSArrayBuffer.getDirectByteLength(resObj) < newLen) || (!direct && JSArrayBuffer.getByteLength(resObj) < newLen)) {
                errorBranch.enter();
                throw Errors.createTypeError("insufficient length constructed");
            }
            // NOTE: Side-effects of the above steps may have detached O.
            if (!getContext().getTypedArrayNotDetachedAssumption().isValid() && JSArrayBuffer.isDetachedBuffer(thisObj)) {
                // yes, check again! see clause 22 of ES 6 24.1.4.3.
                errorBranch.enter();
                throw Errors.createTypeError("cannot slice detached buffer");
            }
        }

        @Specialization(guards = "isJSHeapArrayBuffer(thisObj)")
        protected DynamicObject slice(DynamicObject thisObj, Object begin0, Object end0) {
            int len = JSArrayBuffer.getByteArray(thisObj).length;
            int begin = getStart(begin0, len);
            int finalEnd = getEnd(end0, len);
            return slice(thisObj, begin, finalEnd);
        }

        @Specialization(guards = "isJSDirectArrayBuffer(thisObj)")
        protected DynamicObject sliceDirect(DynamicObject thisObj, int begin, int end) {
            ByteBuffer byteBuffer = JSArrayBuffer.getDirectByteBuffer(thisObj);
            int byteLength = JSArrayBuffer.getDirectByteLength(thisObj);
            int clampedBegin = clampIndex(begin, 0, byteLength);
            int clampedEnd = clampIndex(end, clampedBegin, byteLength);
            int newLen = clampedEnd - clampedBegin;

            DynamicObject resObj = constructNewArrayBuffer(thisObj, newLen);
            checkErrors(resObj, thisObj, newLen, true);

            ByteBuffer resBuffer = JSArrayBuffer.getDirectByteBuffer(resObj);
            sliceDirectIntl(byteBuffer, clampedBegin, clampedEnd, resBuffer);
            return resObj;
        }

        @Specialization(guards = "isJSDirectArrayBuffer(thisObj)")
        protected DynamicObject sliceDirect(DynamicObject thisObj, Object begin0, Object end0) {
            int len = JSArrayBuffer.getDirectByteLength(thisObj);
            int begin = getStart(begin0, len);
            int end = getEnd(end0, len);
            return sliceDirect(thisObj, begin, end);
        }

        @Specialization(guards = {"!isJSHeapArrayBuffer(thisObj)", "!isJSDirectArrayBuffer(thisObj)"})
        protected static DynamicObject error(Object thisObj, @SuppressWarnings("unused") Object begin0, @SuppressWarnings("unused") Object end0) {
            throw Errors.createTypeErrorIncompatibleReceiver(thisObj);
        }
    }
}