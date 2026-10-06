package com.example.identity.simulation.kms.api.v1

import com.example.identity.demo.demo_mode.DemoSurface
import com.example.identity.contract.tool_api.kms.KmsKeyInfo
import com.example.identity.simulation.kms.KmsTransit
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The operator's console of the simulated KMS: which keys and versions exist, and the two
 * operations a key custodian would run there, rotate and retire. Only in demo mode (ADR-36). A
 * tester rotates the KEK here and watches new accounts carry the new version while old ones
 * still read; retiring a version below one still in use shows what a lost key means.
 */
@RestController
@DemoSurface
@RequestMapping("/mock-kms")
@Tag(name = "Mock KMS", description = "Simulierter Schluesseldienst - kein Endpunkt dieser Anwendung, sondern der Fremddienst")
class KmsMockController(private val kms: KmsTransit) {

    @GetMapping("keys")
    @Operation(summary = "Schluessel und Versionen", description = "Alle Schluessel mit ihren Versionen; Schluesselmaterial verlaesst den Dienst nie.")
    fun keys(): List<KmsKeyInfo> = kms.allKeys()

    @PostMapping("keys/{name}/rotation")
    @Operation(summary = "Schluessel rotieren", description = "Legt eine neue Version an. Sie packt ab jetzt ein und signiert; aeltere Versionen packen weiter aus und verifizieren.")
    fun rotate(@PathVariable name: String): KmsKeyInfo = kms.rotate(name)

    @PostMapping("keys/{name}/retirement")
    @Operation(summary = "Versionen zurueckziehen", description = "Alle Versionen unterhalb von `below` packen nicht mehr aus und verifizieren nicht mehr; ihr Material wird vernichtet.")
    fun retire(@PathVariable name: String, @RequestParam below: Int): KmsKeyInfo = kms.retireBelow(name, below)

    /** A key the service does not have. */
    @ExceptionHandler(IllegalStateException::class)
    fun unknownKey(e: IllegalStateException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))

    /** A version outside what the key has. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun badVersion(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
