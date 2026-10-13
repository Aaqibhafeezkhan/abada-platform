package com.abada.engine.authoring;

import com.abada.engine.parser.AplSchema;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds what the authoring model is told about APL from the language's own JSON Schema (E17), so the prompt
 * lists exactly the node types and fields the engine accepts and cannot drift from them: a node type added to
 * the schema is in the next prompt without touching this class.
 */
public final class AplPromptBuilder {
    private static final int MAX_DESCRIPTION = 110;

    private AplPromptBuilder() {}

    /** The node types of the schema, with their required fields. */
    public static Map<String, List<String>> nodeTypes() {
        Map<String, List<String>> types = new TreeMap<>();
        JsonNode defs = AplSchema.instance().document().path("$defs");
        defs.fields().forEachRemaining(def -> {
            JsonNode type = def.getValue().path("properties").path("type").path("const");
            if (!type.isTextual()) return;
            List<String> required = new ArrayList<>();
            def.getValue().path("required").forEach(name -> required.add(name.asText()));
            types.put(type.asText(), required);
        });
        return types;
    }

    /** A compact description of every node type: required fields, then each field with what the schema says of it. */
    public static String languageReference(List<String> allowedAgentModels) {
        JsonNode document = AplSchema.instance().document();
        JsonNode defs = document.path("$defs");
        StringBuilder out = new StringBuilder();
        out.append("The document is YAML with `version: abada.io/v1`, `metadata` (key, name, optional owner, ")
                .append("category, variables) and `flow` (entry: the id of the single webhook node, nodes: a list).\n")
                .append("Node types and their fields (required fields are marked *):\n");
        defs.fields().forEachRemaining(def -> {
            JsonNode type = def.getValue().path("properties").path("type").path("const");
            if (!type.isTextual()) return;
            out.append("- ").append(type.asText()).append(": ");
            List<String> required = new ArrayList<>();
            def.getValue().path("required").forEach(name -> required.add(name.asText()));
            List<String> fields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> properties = def.getValue().path("properties").fields();
            while (properties.hasNext()) {
                var property = properties.next();
                if (property.getKey().equals("type") || property.getKey().equals("ui")) continue;
                fields.add(property.getKey() + (required.contains(property.getKey()) ? "*" : "")
                        + hint(property.getValue(), defs, allowedAgentModels));
            }
            for (String name : required) {
                if (!name.equals("type") && fields.stream().noneMatch(field -> field.startsWith(name))) {
                    fields.add(name + "*");
                }
            }
            out.append(String.join("; ", fields)).append('\n');
        });
        return out.toString();
    }

    private static String hint(JsonNode property, JsonNode defs, List<String> allowedAgentModels) {
        List<String> notes = new ArrayList<>();
        JsonNode resolved = property;
        if (property.has("$ref")) {
            String name = property.get("$ref").asText().replaceFirst("^.*/", "");
            if (name.equals("agentModel")) {
                notes.add("one of " + String.join("|", allowedAgentModels));
            } else {
                notes.add(name);
            }
            resolved = defs.path(name);
        }
        if (resolved.has("enum")) {
            List<String> values = new ArrayList<>();
            resolved.get("enum").forEach(value -> values.add(value.asText()));
            if (!notes.contains("one of " + String.join("|", values)) && !values.isEmpty() && values.size() <= 12) {
                notes.add("one of " + String.join("|", values));
            }
        } else if (resolved.has("const")) {
            notes.add("always " + resolved.get("const").asText());
        } else if (resolved.has("type") && resolved.get("type").isTextual() && notes.isEmpty()) {
            notes.add(resolved.get("type").asText());
        }
        if (resolved.has("minimum") || resolved.has("maximum")) {
            notes.add((resolved.has("minimum") ? resolved.get("minimum").asText() : "") + ".."
                    + (resolved.has("maximum") ? resolved.get("maximum").asText() : ""));
        }
        String description = resolved.path("description").asText(property.path("description").asText(""));
        if (!description.isBlank()) {
            String first = description.strip().split("(?<=[.])\\s", 2)[0];
            notes.add(first.length() > MAX_DESCRIPTION ? first.substring(0, MAX_DESCRIPTION) + "…" : first);
        }
        return notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")";
    }
}
