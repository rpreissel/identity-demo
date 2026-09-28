<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#-- One step "redirect" (IdentNectRendererFactory): a link takes the user to Nect's jump page,
     and Nect sends them back to this step's own action URL with ?nectCaseId=... - a GET Keycloak
     treats like this form's post. Without jumpUrl the last attempt failed; "Erneut versuchen"
     opens a fresh case. "Zurück" leaves the tool (orchestrator_back). -->
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <#if hint??>
            <p class="orchestrator-subtitle">${hint}</p>
        </#if>
        <p class="orchestrator-hint">${t.of("Sie wechseln zu Nect und weisen sich dort mit Personalausweis, Reisepass oder EUDI-Wallet aus. Danach kommen Sie automatisch hierher zurück.")}</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="orchestrator-actions">
                <#if jumpUrl??>
                    <a id="nect-jump" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" href="${jumpUrl}">${t.of("Weiter zu Nect")}</a>
                <#else>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}"
                            type="submit" name="retry" value="true">${t.of("Erneut versuchen")}</button>
                </#if>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_back" value="true" formnovalidate>${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
