package com.abada.engine.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compares the shape of a generated process with what the request plainly asks for. A request that branches
 * ("if ..., otherwise ...") needs a node that routes, and a request that asks a person to approve or validate
 * needs a human step. A candidate that folds such a request into one agent prompt is valid APL, but not the
 * process that was asked for, so the gap is fed back to the model. The check reads cue words only; it never
 * decides on its own what the process must be, and its messages never quote the request.
 */
final class AplStructureCheck {
    private static final Pattern BRANCHING = Pattern.compile(
            "\\b(if|else|otherwise|unless|whether|depending on|in case)\\b");
    private static final Pattern HUMAN = Pattern.compile(
            "\\b(approv\\w*|validat\\w*|review\\w*|sign[- ]?off|administrators?|admins?|managers?|supervisors?"
                    + "|a human|a person)\\b");
    private static final Set<String> ROUTING = Set.of("condition", "decision-table", "inclusive", "event-gateway");
    private static final Set<String> HUMAN_STEPS = Set.of("human-input", "approval-gate");
    private static final YAMLMapper YAML = new YAMLMapper();

    private AplStructureCheck() {}

    /** One line per missing part, or an empty list when the shape matches the request or cannot be read. */
    static List<String> gaps(String request, String aplSource) {
        JsonNode nodes;
        try {
            nodes = YAML.readTree(aplSource).path("flow").path("nodes");
        } catch (Exception exception) {
            return List.of();
        }
        Set<String> types = new HashSet<>();
        boolean routes = false;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText();
            types.add(type);
            if (node.has("routes") || node.has("outcomes")) routes = true;
        }
        String text = request.toLowerCase(Locale.ROOT);
        List<String> gaps = new ArrayList<>();
        if (BRANCHING.matcher(text).find() && !routes && types.stream().noneMatch(ROUTING::contains)) {
            gaps.add("The request has conditional steps, but the process has no condition, decision-table or "
                    + "routing node: each \"if ... otherwise\" must become its own deterministic branch.");
        }
        if (HUMAN.matcher(text).find() && types.stream().noneMatch(HUMAN_STEPS::contains)) {
            gaps.add("The request asks a person or role to approve, validate or review, but the process has no "
                    + "human-input node with assignees for that role.");
        }
        return gaps;
    }
}
