/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.util;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.tregex.dfa.DFAStateNodeBuilder;
import com.oracle.truffle.regex.tregex.dfa.NFATransitionSet;
import com.oracle.truffle.regex.tregex.matchers.AnyMatcher;
import com.oracle.truffle.regex.tregex.matchers.BitSetMatcher;
import com.oracle.truffle.regex.tregex.matchers.CharMatcher;
import com.oracle.truffle.regex.tregex.matchers.EmptyMatcher;
import com.oracle.truffle.regex.tregex.matchers.MatcherBuilder;
import com.oracle.truffle.regex.tregex.matchers.SingleCharMatcher;
import com.oracle.truffle.regex.tregex.matchers.SingleRangeMatcher;
import com.oracle.truffle.regex.tregex.nodes.DFAStateNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class DFAExport {

    @CompilerDirectives.TruffleBoundary
    public static void exportDot(Map<NFATransitionSet, DFAStateNodeBuilder> stateMap, short[] entryStates, String path, boolean shortLabels) {
        TreeSet<Short> entryIDs = new TreeSet<>();
        for (short i : entryStates) {
            entryIDs.add(i);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(path))) {
            writer.write("digraph finite_state_machine {");
            writer.newLine();
            String finalStates = stateMap.values().stream().filter(DFAStateNodeBuilder::isFinalState).map(
                            s -> DotExport.escape(dotState(s, shortLabels))).collect(Collectors.joining("\" \""));
            if (!finalStates.isEmpty()) {
                writer.write(String.format("    node [shape = doublecircle]; \"%s\";", finalStates));
                writer.newLine();
            }
            String anchoredFinalStates = stateMap.values().stream().filter(DFAStateNodeBuilder::isAnchoredFinalState).map(
                            s -> DotExport.escape(dotState(s, shortLabels))).collect(Collectors.joining("\" \""));
            if (!anchoredFinalStates.isEmpty()) {
                writer.write(String.format("    node [shape = Mcircle]; \"%s\";", anchoredFinalStates));
                writer.newLine();
            }
            writer.write("    node [shape = circle];");
            writer.newLine();
            for (DFAStateNodeBuilder state : stateMap.values()) {
                if (entryIDs.contains(state.getId())) {
                    for (int i = 0; i < entryStates.length; i++) {
                        if (entryStates[i] == state.getId()) {
                            DotExport.printConnection(writer, (i < entryStates.length / 2 ? "I^" : "I") + i, dotState(state, shortLabels), "");
                            break;
                        }
                    }
                }
                DFAStateNodeBuilder[] successors = state.getSuccessors();
                MatcherBuilder[] matchers = state.getMatcherBuilders();
                if (successors != null) {
                    for (int i = 0; i < successors.length; i++) {
                        DotExport.printConnection(writer, dotState(state, shortLabels), dotState(successors[i], shortLabels), matchers[i].toString());
                    }
                }
            }
            writer.write("}");
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String dotState(DFAStateNodeBuilder state, boolean shortLabels) {
        return "S" + (shortLabels ? state.getId() : state.stateSetToString());
    }

    @CompilerDirectives.TruffleBoundary
    public static void exportUnitTest(DFAStateNode entry, DFAStateNode[] states) {
        System.out.printf("int initialState = %d;\n", entry.getId());
        System.out.printf("DFAStateNode[] states = createStates(%d);\n", states.length);
        for (DFAStateNode state : states) {
            System.out.printf("states[%d].setSuccessors(new int[] { %d", state.getId(), state.getSuccessors()[0]);
            for (int i = 1; i < state.getSuccessors().length; i++) {
                System.out.printf(", %d", state.getSuccessors()[i]);
            }
            System.out.println(" });");
            System.out.printf("states[%d].setMatchers(new ByteMatcher[] {\n    ", state.getId());
            printMatcher(state.getMatchers()[0]);
            for (int i = 1; i < state.getMatchers().length; i++) {
                System.out.print(",\n    ");
                printMatcher(state.getMatchers()[i]);
            }
            System.out.println("\n});");
            if (state.isFinalState()) {
                System.out.printf("states[%d].setFinalState();\n", state.getId());
            }
        }
    }

    private static void printMatcher(CharMatcher matcher) {
        if (matcher instanceof EmptyMatcher) {
            System.out.print("EmptyByteMatcher.create()");
        }
        if (matcher instanceof SingleCharMatcher) {
            System.out.printf("SingleByteMatcher.create(0x%02x)", (int) ((SingleCharMatcher) matcher).getChar());
        }
        if (matcher instanceof SingleRangeMatcher) {
            System.out.printf("RangeByteMatcher.create(0x%02x, 0x%02x)", (int) ((SingleRangeMatcher) matcher).getLo(), (int) ((SingleRangeMatcher) matcher).getHi());
        }
        if (matcher instanceof BitSetMatcher) {
            long[] words = ((BitSetMatcher) matcher).getBitSet().toLongArray();
            System.out.printf("MultiByteMatcher.create(new CompilationFinalBitSet(new long[] {\n        0x%016xL", words[0]);
            for (int i = 1; i < words.length; i++) {
                System.out.printf(", 0x%016xL", words[i]);
            }
            System.out.print("}))");
        }
        if (matcher instanceof AnyMatcher) {
            System.out.print("AnyByteMatcher.create()");
        }
    }
}
