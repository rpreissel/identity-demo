package com.example.identity.kcext;

import com.example.identity.kcext.client.OrchestratorClient;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.keycloak.component.ComponentModel;

/** Shared test building blocks: signing keys, federation components and an orchestrator nobody answers. */
public final class KcTestFixtures {

    private KcTestFixtures() {
    }

    public static ECKey key(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ComponentModel component(String id) {
        ComponentModel model = new ComponentModel();
        model.setId(id);
        return model;
    }

    /** Port 1 accepts no connection: every call fails at once with an IOException. */
    public static OrchestratorClient unreachableOrchestrator() {
        return new OrchestratorClient("http://127.0.0.1:1", "keycloak", "orchestrator", key("keycloak-1"));
    }
}
