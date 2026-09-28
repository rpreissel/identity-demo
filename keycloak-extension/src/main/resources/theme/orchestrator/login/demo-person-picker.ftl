<#-- Demo persona picker for the tool-*.ftl pages, like DemoPersonPicker.tsx: a <select> above
     free-text inputs that fills them from a persona and prefills the first one (ADR-28).
     fieldMapJson maps input id to person field, e.g. '{"email":"email"}'. pickerId only matters
     where one page carries two pickers. -->
<#macro personPicker personsJson fieldMapJson pickerId="demoPerson">
    <#if personsJson?? && personsJson != "null">
        <div class="${properties.kcFormGroupClass!} orchestrator-demo-picker">
            <label for="${pickerId}" class="${properties.kcLabelClass!}"><span class="orchestrator-demo-tag">${t.of("Demo")}</span> ${t.of("Testperson übernehmen")}</label>
            <select id="${pickerId}" class="${properties.kcInputClass!}">
                <option value="">${t.of("— manuell eingeben —")}</option>
            </select>
        </div>
        <script>
            (function () {
                var persons = ${personsJson?no_esc};
                var fieldMap = ${fieldMapJson?no_esc};
                var select = document.getElementById('${pickerId}');
                persons.forEach(function (p, i) {
                    var opt = document.createElement('option');
                    opt.value = String(i);
                    opt.textContent = p.givenNames + ' ' + p.familyName + ' (' + (p.email || p.kvnr || p.personId) + ')';
                    select.appendChild(opt);
                });
                function apply(p) {
                    Object.keys(fieldMap).forEach(function (inputId) {
                        var el = document.getElementById(inputId);
                        var key = fieldMap[inputId];
                        if (el && p[key] !== undefined) el.value = p[key] == null ? '' : p[key];
                    });
                }
                select.addEventListener('change', function () {
                    if (select.value === '') return;
                    apply(persons[parseInt(select.value, 10)]);
                });
                // Prefilled from the first persona, like the app's forms - and only then: without
                // demo values (ADR-28) there are no personas, and the form starts empty.
                // The inputs follow this script in the page, so wait until they exist.
                if (persons.length > 0) {
                    select.value = '0';
                    document.addEventListener('DOMContentLoaded', function () { apply(persons[0]); });
                }
            })();
        </script>
    </#if>
</#macro>
