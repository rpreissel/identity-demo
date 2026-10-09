package com.example.identity.kcext.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension's own texts (docs/adr/ADR-033): every template is a literal, and every language's
 * bundle (keycloak-theme/messages) words each template - nothing left over, the same placeholders. Red means:
 * {@code /translate-texts <lang>}.
 */
class KcTextCatalogTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

    @Test
    void everyTemplateIsALiteral() throws Exception {
        assertEquals(List.of(), KcTextCatalog.extension().problems);
    }

    @Test
    void everyLanguageWordsEveryTemplate() throws Exception {
        KcTextCatalog catalog = KcTextCatalog.extension();
        List<String> failures = new ArrayList<>();
        for (String language : List.of("de", "en")) {
            Properties wordings = new Properties();
            Path bundle = Path.of("../keycloak-theme/messages/messages_" + language + ".properties");
            if (Files.exists(bundle)) {
                try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
                    wordings.load(reader);
                }
            }
            for (KcTextCatalog.Entry entry : catalog.entries.values()) {
                String wording = wordings.getProperty(entry.id());
                if (wording == null) failures.add(language + ": missing " + entry.id() + " \"" + entry.template() + "\" - run /translate-texts " + language);
                else if (!placeholders(wording).equals(placeholders(entry.template()))) failures.add(language + ": placeholders differ in " + entry.id());
            }
            Set<String> known = new TreeSet<>();
            catalog.entries.values().forEach(e -> known.add(e.id()));
            for (String id : wordings.stringPropertyNames()) {
                if (!known.contains(id)) failures.add(language + ": left over " + id + " - run /translate-texts " + language);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }


    private static Set<String> placeholders(String wording) {
        Set<String> names = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(wording);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
