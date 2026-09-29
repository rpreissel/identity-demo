<#import "template.ftl" as layout>
<#import "page-notes.ftl" as pageNotes>
<#-- One step (AuthInviteRendererFactory): the number and the one-time password together. The KVNR
     comes first; the Partnernummer only counts without one (ADR-34). -->
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${pageTitle}
    <#elseif section = "form">
        <@pageNotes.notes/>
        <p class="orchestrator-subtitle">${t.of("Geben Sie Ihre Versichertennummer und das Einmalkennwort aus unserem Brief ein.")}</p>
        <form id="kc-orchestrator-tool-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="kvnr" class="${properties.kcLabelClass!}">${t.of("Versichertennummer")}</label>
                <input type="text" id="kvnr" name="kvnr" class="${properties.kcInputClass!}" autocomplete="off"/>
            </div>
            <details class="${properties.kcFormGroupClass!}">
                <summary>${t.of("Ich habe keine Versichertennummer")}</summary>
                <label for="partnernr" class="${properties.kcLabelClass!}">${t.of("Partnernummer")}</label>
                <input type="text" id="partnernr" name="partnernr" class="${properties.kcInputClass!}" placeholder="P000000000"/>
            </details>
            <div class="${properties.kcFormGroupClass!}">
                <label for="code" class="${properties.kcLabelClass!}">${t.of("Einmalkennwort")}</label>
                <input type="text" id="code" name="code" class="${properties.kcInputClass!}" autocomplete="one-time-code"
                       placeholder="XXXX-XXXX-XXXX" required/>
            </div>
            <div class="orchestrator-actions">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}" type="submit">${t.of("Anmelden")}</button>
                <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!}"
                        type="submit" name="orchestrator_back" value="true" formnovalidate>${t.of("Zurück")}</button>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
