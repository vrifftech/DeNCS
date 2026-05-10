// Copyright 2021-2025 DeNCS
// Licensed under the MIT License. See LICENSE in the project root for full license text.

package com.kotor.resource.formats.ncs.analysis;

import com.kotor.resource.formats.ncs.ActionsData;
import com.kotor.resource.formats.ncs.DoTypes;
import com.kotor.resource.formats.ncs.node.AMoveSpCommand;
import com.kotor.resource.formats.ncs.utils.NodeAnalysisData;
import com.kotor.resource.formats.ncs.utils.NodeUtils;
import com.kotor.resource.formats.ncs.utils.SubroutineAnalysisData;
import com.kotor.resource.formats.ncs.utils.SubroutinePathFinder;
import com.kotor.resource.formats.ncs.utils.SubroutineState;
import com.kotor.resource.formats.ncs.utils.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs subroutine prototyping in a deterministic, graph-driven order.
 */
public class PrototypeEngine {
   private static final int MAX_PASSES = 3;
   private final NodeAnalysisData nodedata;
   private final SubroutineAnalysisData subdata;
   private final ActionsData actions;
   private final boolean strict;

   public PrototypeEngine(NodeAnalysisData nodedata, SubroutineAnalysisData subdata, ActionsData actions, boolean strict) {
      this.nodedata = nodedata;
      this.subdata = subdata;
      this.actions = actions;
      this.strict = strict;
   }

   public void run() {
      CallGraphBuilder.CallGraph graph = new CallGraphBuilder(this.nodedata, this.subdata).build();
      Map<Integer, com.kotor.resource.formats.ncs.node.ASubroutine> subByPos = this.indexSubroutines();

      int mainPos = this.nodedata.getPos(this.subdata.getMainSub());
      Set<Integer> reachable = graph.reachableFrom(mainPos);
      if (this.subdata.getGlobalsSub() != null) {
         reachable.addAll(graph.reachableFrom(this.nodedata.getPos(this.subdata.getGlobalsSub())));
      }

      List<Set<Integer>> sccs = SCCUtil.compute(graph.edges());
      for (Set<Integer> scc : sccs) {
         boolean containsReachable = scc.stream().anyMatch(reachable::contains);
         if (!containsReachable) {
            continue;
         }
         this.prototypeComponent(scc, subByPos);
      }

      CallSiteAnalyzer callsiteAnalyzer = new CallSiteAnalyzer(this.nodedata, this.subdata, this.actions);
      Map<Integer, Integer> callsiteParams = callsiteAnalyzer.analyze();
      this.applyCallsiteParamHints(subByPos, callsiteAnalyzer.getInferredParamTypes());
      this.ensureAllPrototyped(subByPos.values(), callsiteParams);
   }

   private void applyCallsiteParamHints(
      Map<Integer, com.kotor.resource.formats.ncs.node.ASubroutine> subByPos,
      Map<Integer, List<Type>> callsiteParamTypes
   ) {
      for (Map.Entry<Integer, List<Type>> entry : callsiteParamTypes.entrySet()) {
         com.kotor.resource.formats.ncs.node.ASubroutine sub = subByPos.get(entry.getKey());
         if (sub == null) {
            continue;
         }
         SubroutineState state = this.subdata.getState(sub);
         List<Type> params = entry.getValue();
         if (params == null || params.isEmpty()) {
            continue;
         }
         // Only apply very conservative widening.  Call-site stack growth can
         // over-count in large include-heavy helpers, so only repair the common
         // proven failure shape: a zero-argument prototype reached exclusively
         // through a single typed argument.  Do not shrink an existing prototype.
         if (subByPos.size() <= 4 && state.getParamCount() == 0 && params.size() == 1
               && params.get(0) != null && params.get(0).isTyped()) {
            state.setParamCount(params.size());
            state.updateParams(new LinkedList<Type>(params));
            if (!state.isPrototyped()) {
               state.startPrototyping();
               if (!state.type().isTyped()) {
                  state.setReturnType(new Type(Type.VT_INTEGER), 0);
               }
               state.stopPrototyping(true);
            }
         }
      }
   }

   private Map<Integer, com.kotor.resource.formats.ncs.node.ASubroutine> indexSubroutines() {
      Map<Integer, com.kotor.resource.formats.ncs.node.ASubroutine> map = new HashMap<>();
      Iterator<com.kotor.resource.formats.ncs.node.ASubroutine> it = this.subdata.getSubroutines();
      while (it.hasNext()) {
         com.kotor.resource.formats.ncs.node.ASubroutine sub = it.next();
         map.put(this.nodedata.getPos(sub), sub);
      }
      return map;
   }

   private void prototypeComponent(Set<Integer> component, Map<Integer, com.kotor.resource.formats.ncs.node.ASubroutine> subByPos) {
      List<com.kotor.resource.formats.ncs.node.ASubroutine> subs = new ArrayList<>();
      for (int pos : component) {
         com.kotor.resource.formats.ncs.node.ASubroutine sub = subByPos.get(pos);
         if (sub != null) {
            subs.add(sub);
         }
      }

      for (int pass = 0; pass < MAX_PASSES; pass++) {
         boolean progress = false;
         for (com.kotor.resource.formats.ncs.node.ASubroutine sub : subs) {
            SubroutineState state = this.subdata.getState(sub);
            if (state.isPrototyped()) {
               continue;
            }

            sub.apply(new SubroutinePathFinder(state, this.nodedata, this.subdata, pass));
            if (state.isBeingPrototyped()) {
               DoTypes dotypes = new DoTypes(state, this.nodedata, this.subdata, this.actions, true);
               sub.apply(dotypes);
               dotypes.done();
               progress = progress || state.isPrototyped();
            }
         }
         if (!progress) {
            break;
         }
      }
   }

   private void ensureAllPrototyped(
      Iterable<com.kotor.resource.formats.ncs.node.ASubroutine> subs,
      Map<Integer, Integer> callsiteParams
   ) {
      for (com.kotor.resource.formats.ncs.node.ASubroutine sub : subs) {
         SubroutineState state = this.subdata.getState(sub);
         if (!state.isPrototyped()) {
            if (this.strict) {
               System.out.println(
                  "Strict signatures: missing prototype for subroutine at " + Integer.toString(this.nodedata.getPos(sub)) + " (continuing)"
               );
            }
            int pos = this.nodedata.getPos(sub);
            int inferredParams = callsiteParams.getOrDefault(pos, 0);
            int movespParams = this.estimateParamsFromMovesp(sub);
            // Prefer the smaller non-zero estimate to avoid over-counting locals;
            // fall back to whichever is available when the other is zero.
            if (inferredParams > 0 && movespParams > 0) {
               inferredParams = Math.min(inferredParams, movespParams);
            } else if (inferredParams == 0 && movespParams > 0) {
               inferredParams = movespParams;
            }
            if (inferredParams < 0) {
               inferredParams = 0;
            }
            state.startPrototyping();
            state.setParamCount(inferredParams);
            // If we failed to prototype the subroutine, pick a safe, compilable default return type.
            // Using void here suppresses later return-slot inference and can corrupt signatures.
            if (!state.type().isTyped()) {
               state.setReturnType(new Type(Type.VT_INTEGER), 0);
            }
            state.ensureParamPlaceholders();
            state.stopPrototyping(true);
         }
      }
   }

   private int estimateParamsFromMovesp(com.kotor.resource.formats.ncs.node.ASubroutine sub) {
      final int[] maxParams = new int[]{0};
      sub.apply(
         new PrunedDepthFirstAdapter() {
            @Override
            public void outAMoveSpCommand(AMoveSpCommand node) {
               int params = NodeUtils.stackOffsetToPos(node.getOffset());
               if (params > maxParams[0]) {
                  maxParams[0] = params;
               }
            }
         }
      );
      return maxParams[0];
   }
}

