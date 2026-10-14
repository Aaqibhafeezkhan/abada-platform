import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.abada.worker.AbadaWorkerClient;
import io.abada.worker.LockedExternalTask;
import io.abada.worker.RequestOptions;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The CRM showcase's external worker for topic {@code crm.create-opportunity}.
 *
 * <p>It runs outside the engine, like any integration you would write: it
 * locks a task over worker protocol v1 (the Abada Java SDK), creates the
 * opportunity in Twenty, and reports the result back as process variables.
 * The engine only advances the process when {@code complete} arrives.
 *
 * <p>Delivery is at-least-once: a lost completion means the task is handed out
 * again. The worker stays idempotent by naming the opportunity after the
 * process instance and reusing an existing one with that name.
 *
 * <p>Run as a single-file program: {@code java -cp "lib/*" CrmWorker.java}.
 */
public class CrmWorker {
    static final String TOPIC = "crm.create-opportunity";
    static final Duration LOCK = Duration.ofSeconds(60);
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    final AbadaWorkerClient abada;
    final String workerId = "crm-worker-" + ProcessHandle.current().pid();
    final String twentyUrl = env("TWENTY_URL", "http://twenty-server:3000");
    final String twentyPublicUrl = env("TWENTY_PUBLIC_URL", "http://crm.localhost");
    final String twentyApiKey = env("TWENTY_API_KEY", null);

    CrmWorker() {
        Supplier<String> token = new ClientCredentials(env("ABADA_OIDC_TOKEN_URL", null),
                env("ABADA_OIDC_CLIENT_ID", "crm-worker"), env("ABADA_OIDC_CLIENT_SECRET", null));
        abada = new AbadaWorkerClient(URI.create(env("ABADA_ENGINE_URL", "http://abada-engine:5601/api")), token);
    }

    public static void main(String[] args) throws Exception {
        new CrmWorker().run();
    }

    void run() throws InterruptedException {
        // A global worker declares the topics it serves once; the engine then
        // hands it tasks of that topic from every project.
        while (true) {
            try {
                abada.registerCapabilities(List.of(TOPIC), List.of());
                break;
            } catch (RuntimeException notReady) {
                log("engine not ready (" + notReady.getMessage() + "), retrying");
                Thread.sleep(5000);
            }
        }
        log("polling " + TOPIC + " as " + workerId);
        while (true) {
            try {
                List<LockedExternalTask> tasks = abada.fetchAndLock(workerId, List.of(TOPIC), LOCK, 4,
                        RequestOptions.defaults());
                for (LockedExternalTask task : tasks) handle(task);
                if (tasks.isEmpty()) Thread.sleep(1000);
            } catch (RuntimeException failure) {
                log("poll failed: " + failure.getMessage());
                Thread.sleep(5000);
            }
        }
    }

    void handle(LockedExternalTask task) {
        Map<String, Object> vars = task.variables();
        try {
            Map<String, Object> result = createOpportunity(task.processInstanceId(), vars);
            abada.complete(task.id(), workerId, result, RequestOptions.defaults());
            log("completed task " + task.id() + " -> opportunity " + result.get("opportunity_id"));
        } catch (Exception failure) {
            // A technical failure: the engine retries after the timeout until
            // the retries run out, then opens an incident for an operator.
            int retries = Math.max(0, (task.retries() == null ? 3 : task.retries()) - 1);
            log("task " + task.id() + " failed (" + failure.getMessage() + "), retries left " + retries);
            abada.fail(task.id(), workerId, failure.getMessage(), failure.toString(), retries,
                    Duration.ofSeconds(15), RequestOptions.defaults());
        }
    }

    Map<String, Object> createOpportunity(String instanceId, Map<String, Object> vars) throws Exception {
        Map<?, ?> lead = map(vars.get("lead"));
        Map<?, ?> research = map(vars.get("research"));
        String company = text(lead.get("company"), "Unknown company");
        String name = company + " - inbound lead " + instanceId.substring(0, 8);

        // Idempotency: a retried task finds the opportunity it already created.
        JsonNode existing = findOne("opportunities", "name[eq]:\"" + name + "\"");
        if (existing != null) return result(existing.path("id").asText(), true);

        String companyId = text(research.get("company_id"), null);
        if (companyId == null) companyId = findOrCreateCompany(company);
        String personId = findOrCreatePerson(lead, companyId);

        // The reviewer's amount wins; the agent's estimate is only the default.
        double amount = number(vars.get("deal_amount"), number(research.get("estimated_amount"), 0));
        Map<String, Object> opportunity = new LinkedHashMap<>();
        opportunity.put("name", name);
        opportunity.put("stage", text(vars.get("deal_stage"), "NEW"));
        opportunity.put("amount", Map.of("amountMicros", Math.round(amount * 1_000_000), "currencyCode", "USD"));
        opportunity.put("companyId", companyId);
        if (personId != null) opportunity.put("pointOfContactId", personId);
        String opportunityId = post("/rest/opportunities", opportunity).path("data")
                .path("createOpportunity").path("id").asText();

        String body = "**Qualified with Abada** (process instance " + instanceId + ")\n\n"
                + "Agent summary: " + text(research.get("summary"), "-") + "\n\n"
                + "Reviewer notes: " + text(vars.get("review_notes"), "-");
        String noteId = post("/rest/notes", Map.of("title", "Sales qualification",
                "bodyV2", Map.of("markdown", body))).path("data").path("createNote").path("id").asText();
        post("/rest/noteTargets", Map.of("noteId", noteId, "targetOpportunityId", opportunityId));
        return result(opportunityId, false);
    }

    Map<String, Object> result(String opportunityId, boolean reused) {
        if (reused) log("reusing opportunity " + opportunityId + " created by an earlier attempt");
        return Map.of("opportunity_id", opportunityId,
                "opportunity_url", twentyPublicUrl + "/object/opportunity/" + opportunityId);
    }

    String findOrCreateCompany(String company) throws Exception {
        JsonNode found = findOne("companies", "name[eq]:\"" + company + "\"");
        if (found != null) return found.path("id").asText();
        return post("/rest/companies", Map.of("name", company)).path("data").path("createCompany")
                .path("id").asText();
    }

    String findOrCreatePerson(Map<?, ?> lead, String companyId) throws Exception {
        String email = text(lead.get("contact_email"), null);
        if (email == null) return null;
        JsonNode found = findOne("people", "emails.primaryEmail[eq]:\"" + email + "\"");
        if (found != null) return found.path("id").asText();
        String[] names = text(lead.get("contact_name"), email).split(" ", 2);
        Map<String, Object> person = Map.of(
                "name", Map.of("firstName", names[0], "lastName", names.length > 1 ? names[1] : ""),
                "emails", Map.of("primaryEmail", email),
                "companyId", companyId);
        return post("/rest/people", person).path("data").path("createPerson").path("id").asText();
    }

    // --- Twenty REST helpers ---------------------------------------------------

    JsonNode get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(twentyUrl + path)).GET());
    }

    JsonNode post(String path, Object body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(twentyUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    JsonNode send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + twentyApiKey)
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Twenty answered HTTP " + response.statusCode() + " for "
                    + request.build().method() + " " + request.build().uri().getPath());
        }
        return JSON.readTree(response.body());
    }

    /** The first record of a collection matching a Twenty REST filter, or null. */
    JsonNode findOne(String collection, String filter) throws Exception {
        JsonNode items = get("/rest/" + collection + "?limit=1&filter=" + encode(filter))
                .path("data").path(collection);
        return items.isArray() && !items.isEmpty() ? items.get(0) : null;
    }

    // --- small utilities ----------------------------------------------------------

    static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> m ? m : Map.of();
    }

    static String text(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    static double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return value == null || value.toString().isBlank() ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        if (fallback == null) throw new IllegalStateException(name + " is required");
        return fallback;
    }

    static void log(String message) {
        System.out.println(Instant.now() + " " + message);
    }

    /** OIDC client credentials, cached until shortly before the token expires. */
    static final class ClientCredentials implements Supplier<String> {
        private final String tokenUrl, clientId, clientSecret;
        private String token;
        private Instant refreshAt = Instant.EPOCH;

        ClientCredentials(String tokenUrl, String clientId, String clientSecret) {
            this.tokenUrl = tokenUrl;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        @Override
        public synchronized String get() {
            if (token != null && Instant.now().isBefore(refreshAt)) return token;
            try {
                String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                        + "&client_secret=" + encode(clientSecret);
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(tokenUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("token endpoint answered HTTP " + response.statusCode());
                }
                JsonNode json = JSON.readTree(response.body());
                token = json.path("access_token").asText();
                refreshAt = Instant.now().plusSeconds(Math.max(30, json.path("expires_in").asLong(300) - 30));
                return token;
            } catch (IOException exception) {
                throw new IllegalStateException("token endpoint unreachable", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
        }
    }
}
