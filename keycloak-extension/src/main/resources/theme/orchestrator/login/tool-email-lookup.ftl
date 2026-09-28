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
        <#assign addressAgain = addressAgain!false>
        <div id="email-code-page">
            <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                <#if step == "codeInput">
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="code" class="${properties.kcLabelClass!}">${t.of("Bestätigungscode")}</label>
                        <input type="text" id="code" name="code" class="${properties.kcInputClass!}" autocomplete="off"/>
                        <#if demoTan??>
                            <span class="orchestrator-hint">${t.of("Demo-Code: {wert}", {"wert": demoTan})}</span>
                        </#if>
                    </div>
                <#else>
                    <@demoPerson.personPicker personsJson=demoPersonsJson! fieldMapJson='{"email":"email"}' />
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="email" class="${properties.kcLabelClass!}">${t.of("E-Mail-Adresse")}</label>
                        <input type="email" id="email" name="email" class="${properties.kcInputClass!}" autocomplete="off"/>
                    </div>
                </#if>
                <div class="orchestrator-actions">
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                    <#if step == "codeInput" && addressAgain>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="button" onclick="showEmailPage('address')">${t.of("Zurück")}</button>
                    <#else>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="submit" name="orchestrator_back" value="true">${t.of("Zurück")}</button>
                    </#if>
                </div>
            </form>
        </div>
        <#if step == "codeInput" && addressAgain>
            <#-- The address again, in the page: only sending it goes to the server (a new code). -->
            <div id="email-address-page" hidden>
                <form id="kc-orchestrator-tool-form-address" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
                    <@demoPerson.personPicker personsJson=demoPersonsJson! pickerId="demoPersonAddress" fieldMapJson='{"emailAgain":"email"}' />
                    <div class="${properties.kcFormGroupClass!}">
                        <label for="emailAgain" class="${properties.kcLabelClass!}">${t.of("E-Mail-Adresse")}</label>
                        <input type="email" id="emailAgain" name="email" class="${properties.kcInputClass!}" autocomplete="off" required/>
                    </div>
                    <div class="orchestrator-actions">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Weiter")}</button>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                                type="button" onclick="showEmailPage('code')">${t.of("Zurück")}</button>
                    </div>
                </form>
            </div>
            <script>
                function showEmailPage(page) {
                    document.getElementById('email-code-page').hidden = page !== 'code';
                    document.getElementById('email-address-page').hidden = page !== 'address';
                }
            </script>
        </#if>
    </#if>
</@layout.registrationLayout>
