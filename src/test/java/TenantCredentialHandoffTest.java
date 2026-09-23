import java.util.List;
import java.util.Map;

public final class TenantCredentialHandoffTest {
    public static void main(String[] args) {
        TenantAccessService.TenantRequest request = new TenantAccessService.TenantRequest(
                "ledger-harbor", "ops@ledger-harbor.example",
                List.of("seller.assets.write", "buyer.updates.read", "orders.handoff.write"));
        assertEquals("ledger-harbor", request.tenantId());
        assertEquals(3, request.scopes().size());

        try {
            TenantAccessService.required(Map.of("id", "user_42"), "key");
            throw new AssertionError("A missing plaintext key must block a completed handoff.");
        } catch (IllegalStateException expected) {
            assertEquals("Missing key in Infrai data.", expected.getMessage());
        }
        System.out.println("Tenant credential decision test passed.");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
}
