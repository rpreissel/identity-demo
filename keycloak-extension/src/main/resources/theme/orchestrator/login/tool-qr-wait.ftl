<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>

        <#if step == "enterCode">
            <#-- The app approved and shows a confirmation code; only typing it here logs this browser
                 in (docs/verfahren/qr.md). No polling on this step. -->
            <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <div class="${properties.kcFormGroupClass!}">
                    <label for="confirmationCode" class="${properties.kcLabelClass!}">${t.of("Code aus der App")}</label>
                    <input type="text" id="confirmationCode" name="confirmationCode" class="${properties.kcInputClass!}"
                           inputmode="numeric" autocomplete="one-time-code" autofocus/>
                    <span class="orchestrator-hint">${t.of("Ihre App zeigt nach der Freigabe einen sechsstelligen Code. Geben Sie ihn hier ein.")}</span>
                </div>
                <div class="orchestrator-actions">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                            type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
                </div>
            </form>
        <#else>
        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <img src="${qrDataUri}" alt="${t.of("QR-Code")}" width="220" height="220"/>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <#-- Manuelle Eingabe ist ein gleichwertiger Weg (docs/journeys/confirm-peer-login.md),
                 deshalb steht der Code auch hier gut lesbar, nicht nur im QR-Bild. -->
            <p>${t.of("Pairing-Code")}: <strong class="orchestrator-qr-code">${pairingCode}</strong></p>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <p class="orchestrator-hint">
                ${t.of("Nach der Freigabe zeigt Ihre App einen Code, den Sie hier eingeben.")}
            </p>
        </div>

        <div class="${properties.kcFormGroupClass!} orchestrator-qr-center">
            <#-- Named target, so a click does not navigate this waiting screen away. Across origins
                 Chrome opens a fresh tab each time (docs/10-frontend.md #6); intent=confirm_peer_login
                 lands correctly in any tab. -->
            <a href="${deepLink}" target="identity-demo-app-kanal">${deepLink}</a>
            <p class="orchestrator-hint">
                ${t.of("Demo-Link: öffnet die App direkt (ohne Kamera) mit vorbefülltem Pairing-Code.")}
            </p>
        </div>

        <form id="kc-orchestrator-tool-form" action="${url.loginAction}" method="post" data-status-url="${statusUrl}">
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_abandon" value="true">${t.of("Abbrechen")}</button>
            </div>
        </form>

        <#-- Asks the status endpoint every two seconds and posts the empty form only once something
             changed, so the page does not reload while waiting (ADR-45). Only an explicit "waiting"
             keeps the page; an unreachable endpoint is asked again. Same rules as qrStatusPoll.ts
             in the Keycloakify theme. -->
        <script>
            (function () {
                var form = document.getElementById("kc-orchestrator-tool-form");
                var done = false;
                form.addEventListener("submit", function () { done = true; });
                function later() { setTimeout(check, 2000); }
                function changed() {
                    if (done) return;
                    done = true;
                    form.submit();
                }
                function check() {
                    if (done) return;
                    fetch(form.dataset.statusUrl, { credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } })
                        .then(function (response) {
                            if (!response.ok) return changed();
                            return response.json().then(function (body) {
                                if (body && body.state === "waiting") later(); else changed();
                            }, changed);
                        }, later);
                }
                later();
            })();
        </script>
        </#if>
    </#if>
</@layout.registrationLayout>
