package com.example.identity.architecture

import java.io.File

/**
 * The repository root, found upwards from the working directory as [InvariantRegisterTest] and
 * `DocReferencesTest` find it. Source rules read files where ArchUnit sees only bytecode.
 */
internal val REPO_ROOT: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle.kts").exists() }

/** The Kotlin sources below [dir], relative to [REPO_ROOT], that pass [include]. */
internal fun kotlinSources(dir: String, include: (File) -> Boolean = { true }): List<File> =
    File(REPO_ROOT, dir).walkTopDown().filter { it.isFile && it.extension == "kt" && include(it) }.toList()

/** Every line of [kotlinSources] that [regex] matches, as `path:line`. */
internal fun sourceLinesMatching(dir: String, regex: Regex, include: (File) -> Boolean = { true }): List<String> =
    kotlinSources(dir, include).flatMap { file ->
        file.readLines().mapIndexedNotNull { i, line -> if (regex.containsMatchIn(line)) "${file.relativeTo(REPO_ROOT)}:${i + 1}" else null }
    }
