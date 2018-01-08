/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
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
    public static void exportDot(Map<NFATransitionSet, DFAStateNodeBuilder> stateMap,
                    short[] anchoredEntries, short[] unAnchoredEntries, String path, boolean shortLabels) {
        TreeSet<Short> anchoredIDs = new TreeSet<>();
        for (short i : anchoredEntries) {
            anchoredIDs.add(i);
        }
        TreeSet<Short> unAnchoredIDs = new TreeSet<>();
        for (short i : unAnchoredEntries) {
            unAnchoredIDs.add(i);
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
                if (anchoredIDs.contains(state.getId())) {
                    for (int i = 0; i < anchoredEntries.length; i++) {
                        if (anchoredEntries[i] == state.getId()) {
                            DotExport.printConnection(writer, "Anchored" + i, dotState(state, shortLabels), "");
                            break;
                        }
                    }
                }
                if (unAnchoredIDs.contains(state.getId())) {
                    for (int i = 0; i < unAnchoredEntries.length; i++) {
                        if (unAnchoredEntries[i] == state.getId()) {
                            DotExport.printConnection(writer, "UnAnchored" + i, dotState(state, shortLabels), "");
                            break;
                        }
                    }
                }
                DFAStateNodeBuilder[] successors = state.getSuccessors();
                MatcherBuilder[] matchers = state.getMatcherBuilders();
                for (int i = 0; i < successors.length; i++) {
                    DotExport.printConnection(writer, dotState(state, shortLabels), dotState(successors[i], shortLabels), matchers[i].toString());
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