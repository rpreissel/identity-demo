<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#import "demo-person-picker.ftl" as demoPerson>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <#if replaces!false>
            <p class="orchestrator-hint">${t.of("Die neue Telefonnummer ersetzt Ihre bisherige, sobald Sie den Code bestätigt haben.")}</p>
        </#if>
        <div id="sms-tan-page">
            <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <#if step == "tanInput">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="tan" class="${properties.kcLabelClass!}">${t.of("SMS-Code")}</label>
                        <input type="text" id="tan" name="tan" class="${properties.kcInputClass!}" autocomplete="one-time-code"/>
                        <#if demoTan??>
                            <span class="orchestrator-hint">${t.of("Demo-Code: {wert}", {"wert": demoTan})}</span>
                        </#if>
                    </div>
                <#else>
                    <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"phoneNumber":"phoneNumber"}' />
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="phoneNumber" class="${properties.kcLabelClass!}">${t.of("Telefonnummer")}</label>
                        <input type="tel" id="phoneNumber" name="phoneNumber" class="${properties.kcInputClass!}" autocomplete="tel"/>
                    </div>
                </#if>
                <div class="orchestrator-actions">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                    <#if step == "tanInput">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="button" onclick="showSmsPage('number')">${t.of("Zurück")}</button>
                    <#else>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="submit" name="orchestrator_back" value="true">${t.of("Zurück")}</button>
                    </#if>
                </div>
            </form>
        </div>
        <#if step == "tanInput">
            <#-- The number again, in the page: only sending it goes to the server (a new code). -->
            <div id="sms-number-page" hidden>
                <form id="kc-orchestrator-tool-form-number" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                    <@demoPerson.personPicker personsJson=demoPersonsJson! pickerId="demoPersonNumber" fieldMapJson='{"phoneNumberAgain":"phoneNumber"}' />
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="phoneNumberAgain" class="${properties.kcLabelClass!}">${t.of("Telefonnummer")}</label>
                        <input type="tel" id="phoneNumberAgain" name="phoneNumber" class="${properties.kcInputClass!}" autocomplete="tel" required/>
                    </div>
                    <div class="orchestrator-actions">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="button" onclick="showSmsPage('tan')">${t.of("Zurück")}</button>
                    </div>
                </form>
            </div>
            <script>
                function showSmsPage(page) {
                    document.getElementById('sms-tan-page').hidden = page !== 'tan';
                    document.getElementById('sms-number-page').hidden = page !== 'number';
                }
            </script>
        </#if>
    </#if>
</@layout.registrationLayout>
