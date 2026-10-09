package com.sendrepute.mail;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomerServiceClientTest {
    @Test void contractAndAuthorizationAreEnforcedBeforeTransport() throws Exception {
        var catalog = new ObjectMapper().readTree(CustomerServices.CONTRACT);
        assertEquals(49, catalog.size());
        var client = new CustomerServiceClient("offline-fixture", Duration.ofSeconds(1));
        assertThrows(SendReputeException.class, () ->
            client.request("customerPurchaseVip", null, Map.of(), Map.of(), false));
        assertThrows(IllegalArgumentException.class, () ->
            client.request("arbitraryUrl", null, Map.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class, () ->
            client.request("customerGetAccount", null, Map.of(), Map.of("redirect", "https://outside.test"), false));
    }
}
