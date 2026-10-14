package com.abada.engine.core.replay;

import com.abada.engine.core.replay.ReplayRunner.Kind;
import com.abada.engine.core.replay.ReplayRunner.Step;
import com.abada.engine.persistence.entity.ActivityHistoryEntity;
import com.abada.engine.persistence.repository.ActivityHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the path of decisions an instance took from its audit history, in the shape {@link ReplayRunner}
 * produces: agent outcomes, routes, reviewer outcomes and decision-table rules. Retries and rejected attempts are
 * not decisions and are left out.
 */
@Component
public class OriginalPathReader {
    private final ActivityHistoryRepository history;
    private final ObjectMapper json;

    public OriginalPathReader(ActivityHistoryRepository history, ObjectMapper json) {
        this.history = history;
        this.json = json;
    }

    public List<Step> read(String instanceId) {
        return steps(history.findByProcessInstanceIdOrderByOccurredAtAsc(instanceId));
    }

    List<Step> steps(List<ActivityHistoryEntity> events) {
        List<Step> path = new ArrayList<>();
        for (ActivityHistoryEntity event : events) {
            JsonNode details = details(event);
            switch (event.getEventType()) {
                case "EXTERNAL_TASK_COMPLETED" -> {
                    if (details.path("agentOutcome").isTextual()) {
                        path.add(new Step(event.getActivityId(), Kind.AGENT, details.path("agentOutcome").asText()));
                    }
                }
                case "ROUTE_TAKEN" -> path.add(new Step(event.getActivityId(), Kind.ROUTE,
                        details.path("route").asText()));
                case "TASK_COMPLETED" -> path.add(new Step(event.getActivityId(), Kind.HUMAN,
                        details.path("outcome").isTextual() ? details.path("outcome").asText() : "completed"));
                case "DECISION_TABLE_APPLIED" -> {
                    List<Integer> rules = new ArrayList<>();
                    details.path("matchedRuleIndexes").forEach(rule -> rules.add(rule.asInt()));
                    path.add(new Step(event.getActivityId(), Kind.DECISION, String.valueOf(rules)));
                }
                default -> { }
            }
        }
        return List.copyOf(path);
    }

    /** The reviewer outcomes of the path per human step, in order: what a replay hands to the same step. */
    public static ReplayRunner.HumanDecisions decisionsOf(List<Step> path) {
        java.util.Map<String, java.util.Deque<String>> queues = new java.util.HashMap<>();
        for (Step step : path) {
            if (step.kind() == Kind.HUMAN) {
                queues.computeIfAbsent(step.activityId(), key -> new java.util.ArrayDeque<>()).add(step.outcome());
            }
        }
        return activity -> java.util.Optional.ofNullable(queues.getOrDefault(activity, new java.util.ArrayDeque<>())
                .poll());
    }

    private JsonNode details(ActivityHistoryEntity event) {
        try {
            return json.readTree(event.getDetailsJson() == null ? "{}" : event.getDetailsJson());
        } catch (java.io.IOException exception) {
            return json.createObjectNode();
        }
    }
}
