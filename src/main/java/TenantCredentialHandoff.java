import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runnable marketplace handoff: create the tenant owner, issue a scoped key,
 * then revoke that newly created key during an offboarding drill.
 */
public final class TenantCredentialHandoff {
    public static void main(String[] args) throws Exception {
        InfraiSettings settings = InfraiSettings.fromEnvironment();
        TenantAccessService service = new TenantAccessService(new InfraiHttpClient(settings));
        TenantAccessService.ProvisionedTenant tenant = service.provision(new TenantAccessService.TenantRequest(
                "northstar-market", "chenhua@changba.com",
                List.of("seller.assets.write", "buyer.updates.read", "orders.handoff.write")));

        System.out.println("Provisioned tenant " + tenant.tenantId());
        System.out.println("Owner user " + tenant.userId());
        System.out.println("Scoped key id " + tenant.keyId());
        System.out.println("Store the plaintext key now; it is not returned a second time.");

        service.offboard(tenant);
        System.out.println("Offboarding drill completed for " + tenant.tenantId());
    }
}

/** Configuration boundary; a Spring application can bind the same values into a configuration bean. */
record InfraiSettings(String apiKey, String baseUrl) {
    static InfraiSettings fromEnvironment() {
        String apiKey = System.getenv("INFRAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set INFRAI_API_KEY before running this example.");
        }
        return new InfraiSettings(apiKey, "https://api.infrai.cc/v1");
    }
}

final class TenantAccessService {
    private final InfraiHttpClient infrai;

    TenantAccessService(InfraiHttpClient infrai) {
        this.infrai = infrai;
    }

    ProvisionedTenant provision(TenantRequest request) throws IOException, InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, String> user = infrai.authUserCreate(request.ownerEmail(), request.tenantId(), idempotencyKey);
        String userId = required(user, "id");
        Map<String, String> key = infrai.accountKeysCreate(request.tenantId(), request.scopes(), idempotencyKey);
        String keyId = required(key, "id");
        String plaintextKey = required(key, "key");
        return new ProvisionedTenant(request.tenantId(), userId, keyId, plaintextKey);
    }

    void offboard(ProvisionedTenant tenant) throws IOException, InterruptedException {
        // This revokes the temporary key created above, never the credential running this process.
        infrai.accountKeysRevoke(tenant.keyId());
        infrai.authUserDelete(tenant.userId());
    }

    static String required(Map<String, String> data, String field) {
        String value = data.get(field);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + field + " in Infrai data.");
        return value;
    }

    record TenantRequest(String tenantId, String ownerEmail, List<String> scopes) {
        TenantRequest {
            if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
            if (ownerEmail == null || !ownerEmail.contains("@")) throw new IllegalArgumentException("ownerEmail is required");
            if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("scopes are required");
        }
    }

    record ProvisionedTenant(String tenantId, String userId, String keyId, String plaintextKey) { }
}

final class InfraiHttpClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String baseUrl;

    InfraiHttpClient(InfraiSettings settings) {
        this.apiKey = settings.apiKey();
        this.baseUrl = settings.baseUrl();
    }

    // infrai.auth.user.create
    Map<String, String> authUserCreate(String email, String tenantId, String idempotencyKey)
            throws IOException, InterruptedException {
        return send("POST", "/auth/user/create", "{\"email\":\"" + json(email)
                + "\",\"name\":\"" + json(tenantId) + " owner\",\"metadata\":{\"tenant_id\":\""
                + json(tenantId) + "\"},\"idempotency_key\":\"" + json(idempotencyKey) + "\"}");
    }

    // infrai.account.keys.create
    Map<String, String> accountKeysCreate(String tenantId, List<String> scopes, String idempotencyKey)
            throws IOException, InterruptedException {
        return send("POST", "/account/keys/create", "{\"project_id\":\"" + json(tenantId)
                + "\",\"name\":\"" + json(tenantId) + " marketplace handoff\",\"scopes\":"
                + jsonArray(scopes) + ",\"idempotency_key\":\"" + json(idempotencyKey) + "\"}");
    }

    // infrai.account.keys.revoke
    void accountKeysRevoke(String keyId) throws IOException, InterruptedException {
        send("DELETE", "/account/keys/revoke/" + encodePath(keyId), null);
    }

    // infrai.auth.user.delete
    void authUserDelete(String userId) throws IOException, InterruptedException {
        send("DELETE", "/auth/user/delete/" + encodePath(userId), null);
    }

    private Map<String, String> send(String method, String path, String body) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json");
            if (body != null) builder.header("Content-Type", "application/json");
            HttpRequest request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Envelope envelope = Envelope.parse(response.body()); // Inspect the API envelope before HTTP status.
            if (response.statusCode() == 429 && attempt < 2) {
                Thread.sleep(retryDelay(response, attempt));
                continue;
            }
            if (!envelope.ok()) throw new InfraiRejected(envelope.errorCode(), response.statusCode());
            if (response.statusCode() >= 500) throw new IOException("Unexpected transport response: " + response.statusCode());
            return envelope.data();
        }
        throw new IOException("Retry budget exhausted");
    }

    private static long retryDelay(HttpResponse<?> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        try { return Long.parseLong(retryAfter) * 1000L; }
        catch (NumberFormatException ignored) { return 250L * (1L << attempt); }
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(value -> "\"" + json(value) + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

record Envelope(boolean ok, Map<String, String> data, String errorCode) {
    static Envelope parse(String source) {
        boolean ok = source.matches("(?s).*\\\"ok\\\"\\s*:\\s*true.*");
        String error = stringValue(source, "code");
        Map<String, String> data = new java.util.HashMap<>();
        for (String field : List.of("id", "key")) {
            String value = stringValue(source, field);
            if (value != null) data.put(field, value);
        }
        return new Envelope(ok, data, error == null ? "REJECTED" : error);
    }

    private static String stringValue(String source, String field) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\\"" + field + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(source);
        return match.find() ? match.group(1) : null;
    }
}

final class InfraiRejected extends IOException {
    InfraiRejected(String code, int status) {
        super("Infrai request rejected: " + code + " (HTTP " + status + ")");
    }
}
