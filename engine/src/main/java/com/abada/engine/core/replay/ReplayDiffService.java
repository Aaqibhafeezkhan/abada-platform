package com.abada.engine.core.replay;

import com.abada.engine.core.replay.ReplayRunner.Step;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares what an instance actually did with what the proposed definition would have done on the same inputs
 * (E15). Pure and deterministic. It reports differences in the path of decisions (agent outcomes, routes, reviewer
 * outcomes, decision-table rules); it never judges which path is better.
 */
public final class ReplayDiffService {

    /** One decision point that differs: the original step and the replayed step at the same position. */
    public record Divergence(int position, Step original, Step replayed) {}

    public record CaseDiff(boolean changed, Divergence firstDivergence, List<String> addedSteps,
            List<String> removedSteps, List<Step> changedOutcomes) {
        public static CaseDiff unchanged() {
            return new CaseDiff(false, null, List.of(), List.of(), List.of());
        }
    }

    /** What the aggregate of a run says about the reviewed agents. */
    public record Aggregate(int cases, int unchanged, int changed, int needsHuman, int needsExternal, int failed,
            int skipped, int originallyOverridden, int overriddenNowDiffering) {}

    public CaseDiff compare(List<Step> original, List<Step> replayed) {
        int shared = Math.min(original.size(), replayed.size());
        Divergence first = null;
        List<Step> changedOutcomes = new ArrayList<>();
        for (int i = 0; i < shared; i++) {
            Step before = original.get(i);
            Step after = replayed.get(i);
            if (before.equals(after)) continue;
            if (first == null) first = new Divergence(i, before, after);
            if (before.activityId().equals(after.activityId()) && before.kind() == after.kind()) {
                changedOutcomes.add(after);
            }
        }
        if (first == null && original.size() != replayed.size()) {
            first = new Divergence(shared, shared < original.size() ? original.get(shared) : null,
                    shared < replayed.size() ? replayed.get(shared) : null);
        }
        if (first == null) return CaseDiff.unchanged();
        return new CaseDiff(true, first, surplus(replayed, original), surplus(original, replayed),
                List.copyOf(changedOutcomes));
    }

    /** Activities that appear more often in {@code from} than in {@code against}, as {@code kind:activity}. */
    private static List<String> surplus(List<Step> from, List<Step> against) {
        Map<String, Integer> counts = new HashMap<>();
        for (Step step : against) counts.merge(label(step), 1, Integer::sum);
        List<String> extra = new ArrayList<>();
        for (Step step : from) {
            int left = counts.merge(label(step), -1, Integer::sum);
            if (left < 0) extra.add(label(step));
        }
        return extra.stream().distinct().toList();
    }

    private static String label(Step step) {
        return step.kind().name().toLowerCase() + ":" + step.activityId() + "=" + step.outcome();
    }

    /** Whether the original path has the reviewer overruling the agent: a review step with a non-accepting outcome. */
    public static boolean overridden(List<Step> original, String reviewNode, String accept) {
        return original.stream().anyMatch(step -> step.kind() == ReplayRunner.Kind.HUMAN
                && step.activityId().equals(reviewNode) && !step.outcome().equals(accept));
    }
}
