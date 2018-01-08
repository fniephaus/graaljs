/*
 * Copyright (c) 2012, 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.tregex.dfa;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.regex.UnsupportedRegexException;
import com.oracle.truffle.regex.result.PreCalculatedResultFactory;
import com.oracle.truffle.regex.tregex.TRegexOptions;
import com.oracle.truffle.regex.tregex.automaton.TransitionBuilder;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.matchers.AnyMatcher;
import com.oracle.truffle.regex.tregex.matchers.CharMatcher;
import com.oracle.truffle.regex.tregex.matchers.MatcherBuilder;
import com.oracle.truffle.regex.tregex.matchers.SingleCharMatcher;
import com.oracle.truffle.regex.tregex.nfa.NFA;
import com.oracle.truffle.regex.tregex.nfa.NFAMatcherState;
import com.oracle.truffle.regex.tregex.nfa.NFAState;
import com.oracle.truffle.regex.tregex.nfa.NFAStateTransition;
import com.oracle.truffle.regex.tregex.nodes.BackwardDFAStateNode;
import com.oracle.truffle.regex.tregex.nodes.CGTrackingDFAStateNode;
import com.oracle.truffle.regex.tregex.nodes.DFACaptureGroupLazyTransitionNode;
import com.oracle.truffle.regex.tregex.nodes.DFAStateNode;
import com.oracle.truffle.regex.tregex.nodes.TRegexDFAExecutorNode;
import com.oracle.truffle.regex.tregex.nodes.TraceFinderDFAStateNode;
import com.oracle.truffle.regex.tregex.nodes.input.InputIterator;
import com.oracle.truffle.regex.tregex.parser.Counter;
import com.oracle.truffle.regex.tregex.util.DFAExport;
import com.oracle.truffle.regex.tregex.util.DFANodeSplit;
import com.oracle.truffle.regex.tregex.util.DebugUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DFAGenerator {

    private final NFA nfa;

    private final Map<NFATransitionSet, DFAStateNodeBuilder> stateMap = new HashMap<>();
    private final ArrayDeque<DFAStateNodeBuilder> expansionQueue = new ArrayDeque<>();

    private final boolean forward;
    private final boolean trackCaptureGroups;
    private final boolean pruneUnambiguousPaths;

    private short nextID = 0;
    private final Counter cgTransitionIDCounter = new Counter.ThresholdCounter(Short.MAX_VALUE, "too many capture group transitions");
    private int maxNumberOfNfaStates = 0;

    private final List<DFACaptureGroupLazyTransitionNode> transitions = new ArrayList<>();
    private final DFATransitionCanonicalizer canonicalizer;

    private static final short[] EMPTY_SHORT_ARRAY = {};

    private final CompilationBuffer compilationBuffer;

    private final List<DFAStateTransitionBuilder> expandDFAConnections = new ArrayList<>();
    private List<DFAStateNodeBuilder[]> expandDFAPruneTraverseCur;
    private List<DFAStateNodeBuilder[]> expandDFAPruneTraverseNext;

    private DFAGenerator(NFA nfa, boolean forward, boolean trackCaptureGroups, boolean pruneUnambiguousPaths, CompilationBuffer compilationBuffer) {
        this.nfa = nfa;
        this.forward = forward;
        this.trackCaptureGroups = trackCaptureGroups;
        this.pruneUnambiguousPaths = pruneUnambiguousPaths;
        this.canonicalizer = new DFATransitionCanonicalizer(trackCaptureGroups);
        this.compilationBuffer = compilationBuffer;
        this.expandDFAPruneTraverseCur = pruneUnambiguousPaths ? new ArrayList<>() : null;
        this.expandDFAPruneTraverseNext = pruneUnambiguousPaths ? new ArrayList<>() : null;
    }

    @TruffleBoundary
    public static TRegexDFAExecutorNode createForwardDFAExecutor(NFA nfa, InputIterator inputIterator, FrameSlot result, FrameSlot lastTransition, boolean trackCaptureGroups,
                    CompilationBuffer compilationBuffer) {
        DFAGenerator gen = new DFAGenerator(nfa, true, trackCaptureGroups, false, compilationBuffer);
        final int numberOfEntryPoints = nfa.getAnchoredEntry().size();
        short[] anchoredEntries = new short[numberOfEntryPoints];
        short[] unAnchoredEntries = new short[numberOfEntryPoints];
        short[] unAnchoredInitCaptureGroups = trackCaptureGroups ? new short[numberOfEntryPoints] : null;
        short[] anchoredInitCaptureGroups = trackCaptureGroups ? new short[numberOfEntryPoints] : null;
        NFAState loopBackMatcher = null;
        DFACaptureGroupTransitionBuilder[] anchoredInitialCGBuilder = trackCaptureGroups ? new DFACaptureGroupTransitionBuilder[numberOfEntryPoints] : null;
        DFACaptureGroupTransitionBuilder[] unAnchoredInitialCGBuilder = trackCaptureGroups ? new DFACaptureGroupTransitionBuilder[numberOfEntryPoints] : null;
        for (int i = 0; i < numberOfEntryPoints; i++) {
            NFATransitionSet anchoredEntryStateSet = NFATransitionSet.create(nfa, true, true, nfa.getAnchoredEntry().get(i).getNext());
            if (nfa.getUnAnchoredEntry().get(i).getNext().isEmpty()) {
                unAnchoredEntries[i] = -1;
            } else {
                final boolean createLoopBack = !trackCaptureGroups && !nfa.getAst().getSource().getFlags().isSticky();
                NFATransitionSet unAnchoredEntryStateSet = NFATransitionSet.create(nfa, true, true, nfa.getUnAnchoredEntry().get(i).getNext());
                if (createLoopBack) {
                    if (i == 0) {
                        loopBackMatcher = nfa.createLoopBackMatcher();
                        unAnchoredEntryStateSet.addAll(loopBackMatcher.getNext());
                    } else {
                        assert loopBackMatcher != null;
                        // add transition to loopBackMatcher
                        unAnchoredEntryStateSet.add(loopBackMatcher.getNext().get(loopBackMatcher.getNext().size() - 1));
                    }
                }
                anchoredEntryStateSet.addAll(unAnchoredEntryStateSet);
                DFAStateTransitionBuilder unAnchoredEntryConnection = new DFAStateTransitionBuilder(null, unAnchoredEntryStateSet);
                DFAStateNodeBuilder unAnchoredEntryState = gen.lookupOrCreateState(unAnchoredEntryConnection);
                unAnchoredEntries[i] = unAnchoredEntryState.getId();
                if (trackCaptureGroups) {
                    unAnchoredInitialCGBuilder[i] = new DFACaptureGroupTransitionBuilder(nfa, unAnchoredEntryConnection, unAnchoredEntryState, true);
                    unAnchoredEntryState.addPrecedingTransition(unAnchoredInitialCGBuilder[i]);
                }
            }
            DFAStateTransitionBuilder anchoredEntryConnection = new DFAStateTransitionBuilder(null, anchoredEntryStateSet);
            DFAStateNodeBuilder anchoredEntryState = gen.lookupOrCreateState(anchoredEntryConnection);
            anchoredEntries[i] = anchoredEntryState.getId();
            if (trackCaptureGroups) {
                anchoredInitialCGBuilder[i] = new DFACaptureGroupTransitionBuilder(nfa, anchoredEntryConnection, anchoredEntryState, true);
                anchoredEntryState.addPrecedingTransition(anchoredInitialCGBuilder[i]);
            }
        }
        DFAStateNode[] states = gen.createFullDFA();
        DFACaptureGroupLazyTransitionNode[] transitionsArray = null;
        if (trackCaptureGroups) {
            for (int i = 0; i < numberOfEntryPoints; i++) {
                DFACaptureGroupLazyTransitionNode ti = anchoredInitialCGBuilder[i].toLazyTransition(gen.cgTransitionIDCounter, compilationBuffer);
                anchoredInitCaptureGroups[i] = ti.getId();
                gen.registerTransition(ti);
                if (unAnchoredInitialCGBuilder[i] != null) {
                    ti = unAnchoredInitialCGBuilder[i].toLazyTransition(gen.cgTransitionIDCounter, compilationBuffer);
                    unAnchoredInitCaptureGroups[i] = ti.getId();
                    gen.registerTransition(ti);
                }
            }
            transitionsArray = new DFACaptureGroupLazyTransitionNode[gen.transitions.size()];
            for (DFACaptureGroupLazyTransitionNode t : gen.transitions) {
                assert transitionsArray[t.getId()] == null;
                transitionsArray[t.getId()] = t;
            }
            assert Arrays.stream(transitionsArray).noneMatch(Objects::isNull);
        }
        if (DebugUtil.DEBUG) {
            DFAExport.exportDot(gen.stateMap, anchoredEntries, unAnchoredEntries, trackCaptureGroups ? "./dfa_cg.gv" : "./dfa.gv", false);
            gen.dumpDFA();
            if (trackCaptureGroups) {
                for (int i = 0; i < numberOfEntryPoints; i++) {
                    System.out.println(anchoredInitialCGBuilder[i].toTable());
                    System.out.println(transitionsArray[anchoredInitCaptureGroups[i]].toTable("AnchoredInitTransition " + i));
                    if (unAnchoredInitialCGBuilder[i] != null) {
                        System.out.println(unAnchoredInitialCGBuilder[i].toTable());
                        System.out.println(transitionsArray[unAnchoredInitCaptureGroups[i]].toTable("UnAnchoredInitTransition " + i));
                    }
                }
                for (DFAStateNode s : states) {
                    System.out.println(s.toTable());
                }
            }
        }
        if (TRegexOptions.TRegexEnableNodeSplitter) {
            states = tryMakeReducible(anchoredEntries, unAnchoredEntries, states);
        }
        return new TRegexDFAExecutorNode(
                        inputIterator,
                        result,
                        lastTransition,
                        anchoredEntries,
                        unAnchoredEntries,
                        states,
                        transitionsArray,
                        trackCaptureGroups,
                        gen.maxNumberOfNfaStates,
                        nfa.getAst().getNumberOfCaptureGroups(),
                        anchoredInitCaptureGroups,
                        unAnchoredInitCaptureGroups);
    }

    @TruffleBoundary
    public static TRegexDFAExecutorNode createBackwardDFAExecutor(NFA nfa, InputIterator inputIterator, FrameSlot result, CompilationBuffer compilationBuffer) {
        final boolean prune = nfa.isTraceFinderNFA() && nfa.hasReverseUnAnchoredEntry();
        DFAGenerator gen = new DFAGenerator(nfa, false, false, prune, compilationBuffer);
        NFATransitionSet anchoredEntry = NFATransitionSet.create(nfa, false, false, nfa.getReverseAnchoredEntry().getPrev());
        short[] unAnchoredEntries = {-1};
        if (nfa.hasReverseUnAnchoredEntry()) {
            anchoredEntry.addAll(nfa.getReverseUnAnchoredEntry().getPrev());
            NFATransitionSet unAnchoredEntry = NFATransitionSet.create(nfa, false, false, nfa.getReverseUnAnchoredEntry().getPrev());
            unAnchoredEntries[0] = gen.lookupOrCreateState(new DFAStateTransitionBuilder(null, unAnchoredEntry)).getId();
        }
        short[] anchoredEntries = {gen.lookupOrCreateState(new DFAStateTransitionBuilder(null, anchoredEntry)).getId()};
        DFAStateNode[] states = gen.createFullDFA();
        if (DebugUtil.DEBUG) {
            DFAExport.exportDot(gen.stateMap, anchoredEntries, unAnchoredEntries, "./dfa_reverse.gv", false);
            System.out.println("REVERSE");
            gen.dumpDFA();
        }
        // TODO: adapt DFANodeSplit for multiple entry points
        if (TRegexOptions.TRegexEnableNodeSplitter) {
            states = tryMakeReducible(anchoredEntries, unAnchoredEntries, states);
        }
        return new TRegexDFAExecutorNode(
                        inputIterator,
                        result,
                        null,
                        anchoredEntries,
                        unAnchoredEntries,
                        states,
                        null,
                        false,
                        0,
                        0,
                        null,
                        null);
    }

    private static DFAStateNode[] tryMakeReducible(short[] anchoredEntries, short[] unAnchoredEntries, DFAStateNode[] states) {
        try {
            return DFANodeSplit.createReducibleGraph(anchoredEntries, unAnchoredEntries, states);
        } catch (DFANodeSplit.DFANodeSplitBailoutException e) {
            return states;
        }
    }

    private DFAStateNode[] createFullDFA() {
        while (!expansionQueue.isEmpty()) {
            expandDFA(expansionQueue.pop());
        }
        DFAStateNode[] ret = new DFAStateNode[stateMap.values().size()];
        for (DFAStateNodeBuilder s : stateMap.values()) {
            CharMatcher[] matchers = (s.getMatcherBuilders().length > 0) ? new CharMatcher[s.getMatcherBuilders().length] : CharMatcher.EMPTY;
            MatcherBuilder acc = MatcherBuilder.createEmpty();
            for (int i = 0; i < matchers.length; i++) {
                MatcherBuilder matcherBuilder = s.getMatcherBuilders()[i];
                acc = acc.union(matcherBuilder, compilationBuffer);
                if (i == matchers.length - 1 && (acc.matchesEverything() || (pruneUnambiguousPaths && !s.isFinalStateSuccessor()))) {
                    // replace the last matcher with an AnyMatcher, since it must always cover the
                    // remaining input space
                    matchers[i] = AnyMatcher.create();
                } else {
                    matchers[i] = matcherBuilder.createMatcher(compilationBuffer);
                }
            }
            if (matchers.length == 1 && matchers[0] == AnyMatcher.INSTANCE) {
                matchers = AnyMatcher.INSTANCE_ARRAY;
            }
            short[] successors = s.getSuccessors().length > 0 ? new short[s.getSuccessors().length] : EMPTY_SHORT_ARRAY;
            short[] cgTransitions = null;
            short[] cgPrecedingTransitions = null;
            if (trackCaptureGroups) {
                cgTransitions = new short[s.getCaptureGroupTransitions().length];
                List<DFACaptureGroupTransitionBuilder> precedingTransitions = s.getPrecedingTransitions();
                assert !precedingTransitions.isEmpty();
                cgPrecedingTransitions = new short[precedingTransitions.size()];
                for (int i = 0; i < precedingTransitions.size(); i++) {
                    DFACaptureGroupTransitionBuilder transitionBuilder = precedingTransitions.get(i);
                    cgPrecedingTransitions[i] = transitionBuilder.toLazyTransition(cgTransitionIDCounter, compilationBuffer).getId();
                }
            }
            boolean loopToSelf = false;
            for (int i = 0; i < successors.length; i++) {
                successors[i] = s.getSuccessors()[i].getId();
                if (successors[i] == s.getId()) {
                    loopToSelf = true;
                }
                assert successors[i] >= 0 && successors[i] < ret.length;
                if (trackCaptureGroups) {
                    final DFACaptureGroupLazyTransitionNode transition = s.getCaptureGroupTransitions()[i].toLazyTransition(cgTransitionIDCounter, compilationBuffer);
                    cgTransitions[i] = transition.getId();
                    registerTransition(transition);
                }
            }
            boolean findSingleChar = matchers.length == 2 &&
                            matchers[0] instanceof SingleCharMatcher &&
                            matchers[1] instanceof AnyMatcher &&
                            successors[1] == s.getId();
            if (trackCaptureGroups) {
                ret[s.getId()] = new CGTrackingDFAStateNode(s.getId(), s.isFinalState(), s.isAnchoredFinalState(), loopToSelf, findSingleChar, successors, matchers,
                                cgTransitions, cgPrecedingTransitions);
            } else if (nfa.isTraceFinderNFA()) {
                ret[s.getId()] = new TraceFinderDFAStateNode(s.getId(), s.isFinalState(), s.isAnchoredFinalState(), loopToSelf, findSingleChar, successors, matchers,
                                s.getBackwardPrefixState(), s.getUnAnchoredResult(), s.getAnchoredResult());
            } else if (forward) {
                ret[s.getId()] = new DFAStateNode(s.getId(), s.isFinalState(), s.isAnchoredFinalState(), loopToSelf, findSingleChar, successors, matchers);
            } else {
                ret[s.getId()] = new BackwardDFAStateNode(s.getId(), s.isFinalState(), s.isAnchoredFinalState(), loopToSelf, findSingleChar, successors, matchers, s.getBackwardPrefixState());
            }
        }
        return ret;
    }

    private void registerTransition(DFACaptureGroupLazyTransitionNode cgTransition) {
        transitions.add(cgTransition);
    }

    private void expandDFA(DFAStateNodeBuilder state) {
        if (pruneUnambiguousPaths && !state.isFinalStateSuccessor()) {
            PreCalculatedResultFactory result = null;
            byte resultIndex = 0;
            boolean allSameResult = true;
            assert !state.getNfaStateSet().isEmpty();
            for (NFAStateTransition transition : state.getNfaStateSet()) {
                NFAState nfaState = transition.getTarget(forward);
                assert nfaState.hasPossibleResults();
                for (int i : nfaState.getPossibleResults()) {
                    if (result == null) {
                        result = nfa.getPreCalculatedResults()[i];
                        resultIndex = (byte) i;
                    } else if (result != nfa.getPreCalculatedResults()[i]) {
                        allSameResult = false;
                        break;
                    }
                }
            }
            if (allSameResult) {
                state.setOverrideFinalState(true);
                state.setUnAnchoredResult(resultIndex);
                state.setSuccessors(new DFAStateNodeBuilder[0]);
                state.setMatcherBuilders(new MatcherBuilder[0]);
                return;
            }
        }
        expandDFAConnections.clear();
        for (NFAStateTransition transition : state.getNfaStateSet()) {
            NFAState nfaState = transition.getTarget(forward);
            if (nfaState instanceof NFAMatcherState) {
                expandDFAConnections.add(connectionBuilder((NFAMatcherState) nfaState));
            }
        }
        DFAStateTransitionBuilder[] successors = canonicalizer.run(expandDFAConnections, compilationBuffer);
        Arrays.sort(successors, Comparator.comparing(TransitionBuilder::getMatcherBuilder));
        DFAStateNodeBuilder[] successorStates = new DFAStateNodeBuilder[successors.length];
        MatcherBuilder[] matchers = new MatcherBuilder[successors.length];
        DFACaptureGroupTransitionBuilder[] captureGroupTransitions = trackCaptureGroups ? new DFACaptureGroupTransitionBuilder[successors.length] : null;
        for (int i = 0; i < successors.length; i++) {
            assert !successors[i].getTargetState().isEmpty();
            DFAStateNodeBuilder lookupSuccessorState = stateMap.get(successors[i].getTargetState());
            if (lookupSuccessorState == null) {
                successorStates[i] = createState(successors[i]);
                expansionQueue.push(successorStates[i]);
            } else {
                if (pruneUnambiguousPaths && (state.isFinalState() || state.isFinalStateSuccessor()) && !lookupSuccessorState.isFinalStateSuccessor()) {
                    reScheduleFinalStateSuccessor(lookupSuccessorState);
                    expandDFAPruneTraverseCur.clear();
                    expandDFAPruneTraverseCur.add(lookupSuccessorState.getSuccessors());
                    while (!expandDFAPruneTraverseCur.isEmpty()) {
                        expandDFAPruneTraverseNext.clear();
                        for (DFAStateNodeBuilder[] tc : expandDFAPruneTraverseCur) {
                            for (DFAStateNodeBuilder s : tc) {
                                if (s.isFinalStateSuccessor()) {
                                    continue;
                                }
                                expandDFAPruneTraverseNext.add(s.getSuccessors());
                                reScheduleFinalStateSuccessor(s);
                            }
                        }
                        List<DFAStateNodeBuilder[]> tmp = expandDFAPruneTraverseCur;
                        expandDFAPruneTraverseCur = expandDFAPruneTraverseNext;
                        expandDFAPruneTraverseNext = tmp;
                    }
                }
                successorStates[i] = lookupSuccessorState;
            }
            if (pruneUnambiguousPaths && (state.isFinalState() || state.isFinalStateSuccessor())) {
                state.setFinalStateSuccessor();
                successorStates[i].setFinalStateSuccessor();
            }
            matchers[i] = successors[i].getMatcherBuilder();
            if (trackCaptureGroups) {
                captureGroupTransitions[i] = new DFACaptureGroupTransitionBuilder(nfa, successors[i], successorStates[i], false);
                successorStates[i].addPrecedingTransition(captureGroupTransitions[i]);
            }
        }
        state.setSuccessors(successorStates);
        state.setMatcherBuilders(matchers);
        state.setCaptureGroupTransitions(captureGroupTransitions);
    }

    private void reScheduleFinalStateSuccessor(DFAStateNodeBuilder finalStateSuccessor) {
        finalStateSuccessor.setFinalStateSuccessor();
        finalStateSuccessor.setOverrideFinalState(false);
        finalStateSuccessor.setUnAnchoredResult(finalStateSuccessor.getNfaStateSet().getPreCalculatedUnAnchoredResult());
        expansionQueue.push(finalStateSuccessor);
    }

    private DFAStateNodeBuilder lookupOrCreateState(DFAStateTransitionBuilder connection) {
        DFAStateNodeBuilder lookup = stateMap.get(connection.getTargetState());
        if (lookup == null) {
            lookup = createState(connection);
            expansionQueue.push(lookup);
        }
        return lookup;
    }

    private DFAStateNodeBuilder createState(DFAStateTransitionBuilder connection) {
        DFAStateNodeBuilder dfaState = createStateInner(connection.getTargetState());
        if (!forward) {
            if (dfaState.getNfaStateSet().containsPrefixStates()) {
                NFATransitionSet prefixStateSet = NFATransitionSet.create(nfa, false, false);
                for (NFAStateTransition transition : dfaState.getNfaStateSet()) {
                    if (transition.getSource().hasPrefixStates()) {
                        prefixStateSet.add(transition);
                    }
                }
                DFAStateNodeBuilder lookupPrefixState = stateMap.get(prefixStateSet);
                if (lookupPrefixState == null) {
                    lookupPrefixState = createStateInner(prefixStateSet);
                    expansionQueue.push(lookupPrefixState);
                }
                dfaState.setBackwardPrefixState(lookupPrefixState.getId());
                lookupPrefixState.setBackwardPrefixState(lookupPrefixState.getId());
            }
        }
        return dfaState;
    }

    private DFAStateNodeBuilder createStateInner(NFATransitionSet transitionSet) {
        if (transitionSet.size() > maxNumberOfNfaStates) {
            maxNumberOfNfaStates = transitionSet.size();
            if (maxNumberOfNfaStates > TRegexOptions.TRegexMaxNumberOfNFAStatesInOneDFAState) {
                throw new UnsupportedRegexException("DFA state size explosion");
            }
        }
        DFAStateNodeBuilder dfaState = new DFAStateNodeBuilder(nextID++, transitionSet);
        stateMap.put(transitionSet, dfaState);
        if (stateMap.size() + (forward ? expansionQueue.size() : 0) > TRegexOptions.TRegexMaxDFASize) {
            throw new UnsupportedRegexException((forward ? (trackCaptureGroups ? "CG" : "Forward") : "Backward") + " DFA explosion");
        }
        return dfaState;
    }

    private DFAStateTransitionBuilder connectionBuilder(NFAMatcherState matcherState) {
        if (forward) {
            return new DFAStateTransitionBuilder(matcherState.getMatcherBuilder(), matcherState.getNext(), nfa, true, true);
        } else {
            return new DFAStateTransitionBuilder(matcherState.getMatcherBuilder(), matcherState.getPrev(), nfa, false, false);
        }
    }

    private void dumpDFA() {
        System.out.println("DFA:");
        for (DFAStateNodeBuilder s : stateMap.values()) {
            System.out.println(s.toTable());
        }
        System.out.println();
    }
}