package com.abada.engine.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies a refinement to an APL document as a short list of operations instead of rewriting the file (E17).
 * The operations name what they touch, so every node they do not name is left exactly as it was; the result is
 * only a candidate, checked afterwards by the parser like any other source.
 *
 * <p>Operations: {@code addNode} (a node object, optionally {@code after} a node id), {@code removeNode} (an id),
 * {@code updateNode} (an id with {@code set} fields and {@code unset} field names) and {@code setEntry} (an id).
 * A patch cannot rename a node: it adds the new node and removes the old one, so every reference is a visible
 * change.
 */
public class AplPatchService {
    public static final int MAX_OPERATIONS = 40;
    private static final Set<String> OPERATIONS = Set.of("addNode", "removeNode", "updateNode", "setEntry");

    public static class PatchException extends RuntimeException {
        public PatchException(String message) {
            super(message);
        }
    }

    public record Result(String source, List<String> touchedNodes) {}

    private final YAMLMapper yaml = (YAMLMapper) new YAMLMapper()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
    private final ObjectMapper json = new ObjectMapper();

    /** Parses the model's answer: {@code {"summary": "...", "operations": [...]}}. */
    public JsonNode parse(String answer) {
        JsonNode tree;
        try {
            tree = json.readTree(answer);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new PatchException("The answer is not JSON: " + exception.getOriginalMessage());
        } catch (java.io.IOException exception) {
            throw new PatchException("The answer cannot be read");
        }
        if (tree == null || !tree.isObject() || !tree.path("operations").isArray()) {
            throw new PatchException("The answer must be an object with an \"operations\" list");
        }
        return tree;
    }

    public Result apply(String baseSource, JsonNode operations) {
        if (!operations.isArray() || operations.isEmpty()) throw new PatchException("The patch has no operations");
        if (operations.size() > MAX_OPERATIONS) {
            throw new PatchException("A patch may have at most " + MAX_OPERATIONS + " operations");
        }
        ObjectNode root;
        try {
            JsonNode tree = yaml.readTree(baseSource);
            if (tree == null || !tree.isObject()) throw new PatchException("The base source is not a YAML mapping");
            root = (ObjectNode) tree;
        } catch (java.io.IOException exception) {
            throw new PatchException("The base source is not valid YAML");
        }
        JsonNode flow = root.path("flow");
        if (!flow.isObject() || !flow.path("nodes").isArray()) throw new PatchException("The base source has no flow.nodes");
        ArrayNode nodes = (ArrayNode) flow.get("nodes");
        Set<String> touched = new LinkedHashSet<>();

        int index = 0;
        for (JsonNode operation : operations) {
            String where = "operations[" + index++ + "]";
            String op = operation.path("op").asText("");
            if (!OPERATIONS.contains(op)) {
                throw new PatchException(where + ": unknown op '" + op + "'; use " + String.join(", ", OPERATIONS));
            }
            switch (op) {
                case "addNode" -> {
                    JsonNode node = operation.path("node");
                    if (!node.isObject() || !node.path("id").isTextual() || !node.path("type").isTextual()) {
                        throw new PatchException(where + ": addNode needs a node object with an id and a type");
                    }
                    String id = node.get("id").asText();
                    if (find(nodes, id) >= 0) throw new PatchException(where + ": a node '" + id + "' already exists");
                    int at = nodes.size();
                    if (operation.hasNonNull("after")) {
                        int after = find(nodes, operation.get("after").asText());
                        if (after < 0) throw new PatchException(where + ": no node '" + operation.get("after").asText() + "' to add after");
                        at = after + 1;
                    }
                    nodes.insert(at, node.deepCopy());
                    touched.add(id);
                }
                case "removeNode" -> {
                    String id = operation.path("id").asText("");
                    int at = find(nodes, id);
                    if (at < 0) throw new PatchException(where + ": no node '" + id + "' to remove");
                    if (id.equals(flow.path("entry").asText())) {
                        throw new PatchException(where + ": '" + id + "' is the entry node; setEntry first");
                    }
                    nodes.remove(at);
                    touched.add(id);
                }
                case "updateNode" -> {
                    String id = operation.path("id").asText("");
                    int at = find(nodes, id);
                    if (at < 0) throw new PatchException(where + ": no node '" + id + "' to update");
                    ObjectNode node = (ObjectNode) nodes.get(at);
                    JsonNode set = operation.path("set");
                    if (!set.isMissingNode() && !set.isObject()) throw new PatchException(where + ": set must be an object");
                    Iterator<String> names = set.fieldNames();
                    while (names.hasNext()) {
                        String field = names.next();
                        if (field.equals("id")) throw new PatchException(where + ": a node cannot be renamed; add the new node and remove the old one");
                        node.set(field, set.get(field).deepCopy());
                    }
                    for (JsonNode field : operation.path("unset")) {
                        if (field.asText().equals("id") || field.asText().equals("type")) {
                            throw new PatchException(where + ": a node's " + field.asText() + " cannot be unset");
                        }
                        node.remove(field.asText());
                    }
                    if (set.isMissingNode() && !operation.has("unset")) {
                        throw new PatchException(where + ": updateNode needs set and/or unset");
                    }
                    touched.add(id);
                }
                case "setEntry" -> {
                    String id = operation.path("id").asText("");
                    if (find(nodes, id) < 0) throw new PatchException(where + ": no node '" + id + "' to be the entry");
                    ((ObjectNode) flow).put("entry", id);
                }
                default -> throw new PatchException(where + ": unsupported op");
            }
        }
        try {
            return new Result(yaml.writeValueAsString(root), List.copyOf(touched));
        } catch (java.io.IOException exception) {
            throw new PatchException("The patched document cannot be written");
        }
    }

    /** The ids of the nodes of a source, in order; empty when it cannot be read. */
    public List<String> nodeIds(String source) {
        List<String> ids = new ArrayList<>();
        try {
            for (JsonNode node : yaml.readTree(source).path("flow").path("nodes")) ids.add(node.path("id").asText());
        } catch (java.io.IOException ignored) {
            return List.of();
        }
        return ids;
    }

    private static int find(ArrayNode nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).path("id").asText().equals(id)) return i;
        }
        return -1;
    }
}
