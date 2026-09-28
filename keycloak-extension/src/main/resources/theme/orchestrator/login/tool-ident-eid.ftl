<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#import "demo-person-picker.ftl" as demoPerson>
<#-- Two pages of one backend step (IdentEidRendererFactory): the card data first, then the PIN.
     "Zurück" on the PIN page is this page's own business, like "Angaben ändern" in the App: it
     shows the card again, and sending it again has the backend check it anew and ask for the PIN
     once more. Only "Zurück" on the card leaves the tool (orchestrator_back). -->
<#macro cardForm formId backToPin>
    <p class="orchestrator-hint">${t.of("Demo-Modus: Das Auslesen der Karte wird simuliert.")}</p>
    <form id="${formId}" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
        <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"familyName":"familyName","givenNames":"givenNames","birthDate":"birthDate","streetAddress":"streetAddress","postalCode":"postalCode","locality":"locality","restrictedId":"restrictedId"}' />
        <div class="orchestrator-grid-2">
            <div class="${properties.kcFormGroupClass!}">
                <label for="familyName" class="${properties.kcLabelClass!}">${t.of("Nachname")}</label>
                <input type="text" id="familyName" name="familyName" class="${properties.kcInputClass!}" required/>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <label for="givenNames" class="${properties.kcLabelClass!}">${t.of("Vorname")}</label>
                <input type="text" id="givenNames" name="givenNames" class="${properties.kcInputClass!}" required/>
            </div>
        </div>
        <div class="${properties.kcFormGroupClass!}">
            <label for="birthDate" class="${properties.kcLabelClass!}">${t.of("Geburtsdatum")}</label>
            <input type="date" id="birthDate" name="birthDate" class="${properties.kcInputClass!}" required/>
        </div>
        <#-- Die Karte liefert Straße und Hausnummer in einem Feld (Street). -->
        <div class="${properties.kcFormGroupClass!}">
            <label for="streetAddress" class="${properties.kcLabelClass!}">${t.of("Straße und Hausnummer")}</label>
            <input type="text" id="streetAddress" name="streetAddress" class="${properties.kcInputClass!}" required/>
        </div>
        <div class="orchestrator-grid-2">
            <div class="${properties.kcFormGroupClass!}">
                <label for="postalCode" class="${properties.kcLabelClass!}">${t.of("PLZ")}</label>
                <input type="text" id="postalCode" name="postalCode" class="${properties.kcInputClass!}" required/>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <label for="locality" class="${properties.kcLabelClass!}">${t.of("Ort")}</label>
                <input type="text" id="locality" name="locality" class="${properties.kcInputClass!}" required/>
            </div>
        </div>
        <#-- Änderbar, obwohl eine echte Karte den Wert fest mitbringt: nur so lässt sich eine zweite
             Karte derselben Person durchspielen (ADR-19). -->
        <div class="${properties.kcFormGroupClass!}">
            <label for="restrictedId" class="${properties.kcLabelClass!}">${t.of("Restricted-ID (kartengebunden)")}</label>
            <input type="text" id="restrictedId" name="restrictedId" class="${properties.kcInputClass!}" required/>
        </div>
        <div class="orchestrator-actions">
            <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
            <#if backToPin>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="button" onclick="showEidPage('pin')">${t.of("Zurück")}</button>
            <#else>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_back" value="true" formnovalidate>${t.of("Zurück")}</button>
            </#if>
        </div>
    </form>
</#macro>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <#if cardPage>
            <@cardForm formId="kc-orchestrator-tool-form" backToPin=false/>
        <#else>
            <div id="eid-pin-page">
                <p class="orchestrator-subtitle">${t.of("Geben Sie Ihre sechsstellige eID-PIN ein.")}</p>
                <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="pin" class="${properties.kcLabelClass!}">${t.of("eID-PIN")}</label>
                        <input type="text" id="pin" name="pin" class="${properties.kcInputClass!}" autocomplete="off" value="123456" required/>
                    </div>
                    <div class="orchestrator-actions">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Identifizieren")}</button>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="button" onclick="showEidPage('card')">${t.of("Zurück")}</button>
                    </div>
                </form>
            </div>
            <div id="eid-card-page" hidden>
                <@cardForm formId="kc-orchestrator-tool-form-card" backToPin=true/>
            </div>
            <script>
                function showEidPage(page) {
                    document.getElementById('eid-pin-page').hidden = page !== 'pin';
                    document.getElementById('eid-card-page').hidden = page !== 'card';
                }
            </script>
        </#if>
    </#if>
</@layout.registrationLayout>
