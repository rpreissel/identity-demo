package com.example.identity.architecture

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The repository root, found upwards from the working directory as [InvariantRegisterTest] and
 * `DocReferencesTest` find it. Source rules read files where ArchUnit sees only bytecode.
 */
internal val REPO_ROOT: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle.kts").exists() }

/** Each directory's Kotlin files with their lines, read once and shared by the source rules. */
private val sourcesByDir = ConcurrentHashMap<String, List<Pair<File, List<String>>>>()

private fun linesBelow(dir: String): List<Pair<File, List<String>>> = sourcesByDir.getOrPut(dir) {
    File(REPO_ROOT, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it to it.readLines() }.toList()
}

/** The Kotlin sources below [dir], relative to [REPO_ROOT], that pass [include]. */
internal fun kotlinSources(dir: String, include: (File) -> Boolean = { true }): List<File> =
    linesBelow(dir).map { it.first }.filter(include)

/** Every line of [kotlinSources] that [regex] matches, as `path:line`. */
internal fun sourceLinesMatching(dir: String, regex: Regex, include: (File) -> Boolean = { true }): List<String> =
    linesBelow(dir).filter { (file, _) -> include(file) }.flatMap { (file, lines) ->
        lines.mapIndexedNotNull { i, line -> if (regex.containsMatchIn(line)) "${file.relativeTo(REPO_ROOT)}:${i + 1}" else null }
    }
