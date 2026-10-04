package com.example.identity.core.orchestrator.support

import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Fails a request whose success status the contract does not declare for its operation
 * (api/openapi.yaml). OpenApiSnapshotTest secures the contract's shape against the code's
 * annotations, not against what the server actually sends: a controller answering 201 while its
 * operation declares 200 passes the snapshot. Every integration test that talks to the server
 * through [com.example.identity.core.orchestrator.IntegrationTestSupport] runs through this check.
 *
 * Only 2xx: error statuses come from the shared exception handler and are not declared per operation.
 * Paths outside the App contract (admin, demo, simulations) have no operation and pass unchecked.
 */
class ContractStatusCheck : ClientHttpRequestInterceptor {

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        val response = execution.execute(request, body)
        val status = response.statusCode.value()
        if (status in 200..299) {
            val operation = operationFor(request.method, request.uri.path)
            if (operation != null && status !in operation.successCodes) {
                throw AssertionError(
                    "${request.method} ${operation.path} answered $status, api/openapi.yaml declares ${operation.successCodes}"
                )
            }
        }
        return response
    }

    private data class Operation(val method: HttpMethod, val path: String, val pattern: Regex, val successCodes: Set<Int>) {
        /** More literal segments win: `/tools/{id}/auth-kobil/pin-releases` before `/tools/{id}/{x}/...`. */
        val variables = PATH_VARIABLE.findAll(path).count()
    }

    companion object {
        private val PATH_VARIABLE = Regex("\\{[^/}]+}")

        private val operations: List<Operation> by lazy { load(File("api/openapi.yaml")) }

        private fun operationFor(method: HttpMethod, path: String): Operation? =
            operations.firstOrNull { it.method == method && it.pattern.matches(path) }

        @Suppress("UNCHECKED_CAST")
        private fun load(contract: File): List<Operation> {
            val root = contract.inputStream().use { Yaml().load<Map<String, Any?>>(it) }
            val paths = root["paths"] as Map<String, Map<String, Any?>>
            return paths.flatMap { (path, item) ->
                val pattern = Regex(path.split(PATH_VARIABLE).joinToString("[^/]+") { Regex.escape(it) })
                item.mapNotNull { (method, spec) ->
                    val responses = (spec as? Map<String, Any?>)?.get("responses") as? Map<Any?, Any?> ?: return@mapNotNull null
                    val successCodes = responses.keys.mapNotNull { it.toString().toIntOrNull() }.filter { it in 200..299 }.toSet()
                    Operation(HttpMethod.valueOf(method.uppercase()), path, pattern, successCodes)
                }
            }.sortedBy { it.variables }
        }
    }
}
