package com.example.identity.kcext.webtool.qr;

/** Web-channel counterpart of `auth-qr-lookup` - account unknown until the app side reveals it. */
public class AuthQrLookupRendererFactory extends QrWaitRendererFactory {

    public static final String PROVIDER_ID = "auth-qr-lookup";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

}
