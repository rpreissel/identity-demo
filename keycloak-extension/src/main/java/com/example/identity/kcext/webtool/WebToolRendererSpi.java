package com.example.identity.kcext.webtool;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** Registers {@link WebToolRenderer} as a Keycloak provider SPI. */
public class WebToolRendererSpi implements Spi {

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public String getName() {
        return "webToolRenderer";
    }

    @Override
    public Class<? extends Provider> getProviderClass() {
        return WebToolRenderer.class;
    }

    @Override
    public Class<? extends ProviderFactory> getProviderFactoryClass() {
        return WebToolRendererFactory.class;
    }
}
