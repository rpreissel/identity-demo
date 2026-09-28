package com.example.identity.contract.texts

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.Schema
import java.security.MessageDigest

/**
 * A user-facing text, written as its German source wording with named `{placeholders}`:
 * `Text("Dieser {typ}-Wert …", "typ" to type)`. It leaves the backend as a reference ([TextRef]);
 * every language, German included, is a reworded copy per bundle (ADR-33). The template must be a
 * string literal, because `TextCatalog` collects it from the bytecode. A [Text] argument is
 * translated itself, anything else shown as it is.
 */
@Schema(implementation = TextRef::class)
class Text(template: String, vararg args: Pair<String, Any?>) {

    val template: String = template

    /** Values shown as they are. */
    val args: Map<String, String>

    /** Values that are texts themselves. */
    val texts: Map<String, List<Text>>

    init {
        val plain = linkedMapOf<String, String>()
        val nested = linkedMapOf<String, List<Text>>()
        args.forEach { (name, value) ->
            when {
                value is Text -> nested[name] = listOf(value)
                value is List<*> && value.all { it is Text } -> nested[name] = value.map { it as Text }
                else -> plain[name] = value?.toString().orEmpty()
            }
        }
        this.args = plain
        this.texts = nested
    }

    val id: String get() = idOf(template)

    /**
     * What goes over the wire - the reference, and the template only while no bundle has a
     * wording for it in every language yet ([TextBundle.wordedEverywhere]).
     */
    @JsonValue
    fun toRef(): TextRef {
        onWire?.invoke(this)
        return TextRef(
            id, args.ifEmpty { null }, texts.ifEmpty { null }?.mapValues { (_, list) -> list.map { it.toRef() } },
            template = template.takeUnless { TextBundle.wordedEverywhere(id) }
        )
    }

    override fun equals(other: Any?): Boolean =
        other is Text && other.template == template && other.args == args && other.texts == texts

    override fun hashCode(): Int = listOf(template, args, texts).hashCode()

    override fun toString(): String = "Text($template, $args, $texts)"

    companion object {
        /**
         * The reference of a template, derived from it alone, so a changed template gets a new id
         * and no translation goes stale unnoticed (ADR-33). A slug plus 6 hex digits of its SHA-256.
         * Frontend and Keycloak compute the same; `TextIdTest` pins shared samples.
         */
        fun idOf(template: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(template.toByteArray()).take(3).joinToString("") { "%02x".format(it) }
            val slug = slugOf(template)
            return if (slug.isEmpty()) hash else "$slug-$hash"
        }

        /** Lowercase, German umlauts spelled out, every other run of characters a `-`, at most [SLUG_MAX] at a word boundary. */
        private fun slugOf(template: String): String {
            val words = template.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(Regex("[^a-z0-9]+"), "-").trim('-')
            if (words.length <= SLUG_MAX) return words
            val cut = words.substring(0, SLUG_MAX + 1).lastIndexOf('-')
            return (if (cut > 0) words.substring(0, cut) else words.substring(0, SLUG_MAX)).trim('-')
        }

        private const val SLUG_MAX = 40

        /** The placeholder names a template or wording uses - the same set in every language. */
        fun placeholdersOf(wording: String): Set<String> =
            Regex("\\{([A-Za-z][A-Za-z0-9_]*)}").findAll(wording).map { it.groupValues[1] }.toSet()

        /** Tests hook in here to check every reference sent out against the collected catalog. */
        @Volatile
        internal var onWire: ((Text) -> Unit)? = null
    }
}

/** A [Text] as clients see it: resolve [key] in the texts bundle, fill `{name}` from [args] as is and from [texts] translated. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A text reference: look `key` up in the texts bundle (GET .../texts/{lang}), fill `{name}` placeholders from `args` (as is) or `texts` (resolved the same way, several joined with \", \").")
data class TextRef(
    @field:Schema(example = "account-not-found-08a2ef") val key: String,
    /** Absent when the text has no plain values. */
    val args: Map<String, String>? = null,
    /** Absent when the text has no nested texts. */
    val texts: Map<String, List<TextRef>>? = null,
    /** The developer's wording, sent only while the texts bundle has none for [key] yet - show it instead of the key. */
    @field:Schema(description = "The developer's wording, sent only while the texts bundle has none for key yet - show it instead of the key.")
    val template: String? = null
)
