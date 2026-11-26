// Validates native tool arguments against advertised JSON Schemas without changing values or fetching references.
package com.fghbuild.gomode.voice

import com.fghbuild.mcp.sdk.v1.ToolDescriptor
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.util.Locale

// Compile once per tool configuration. Default dialect is 2020-12; $schema can select another supported dialect.
internal class ToolCallValidator(
    tools: List<ToolDescriptor>,
) {
    private val schemas: Map<String, Schema>
    private val mapper =
        JsonMapper
            .builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    init {
        require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate local tool names" }
        val config =
            SchemaRegistryConfig
                .builder()
                .locale(Locale.ROOT)
                .losslessNarrowing(true)
                .build()
        schemas =
            tools.associate { tool ->
                val definition = requireNotNull(tool.inputSchema) { "Missing input schema for tool ${tool.name}" }
                // Isolate schema identifiers between tools. Default resource loading does not fetch remote schemas.
                val registry =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                        builder.nodeReader { it.jsonMapper(mapper) }.schemaRegistryConfig(config)
                    }
                val schema = registry.getSchema(definition.toString(), InputFormat.JSON)
                val metaSchema = registry.getSchema(SchemaLocation.of(schema.schemaContext.dialect.id))
                require(metaSchema.validate(definition.toString(), InputFormat.JSON).isEmpty()) {
                    "Invalid input schema for tool ${tool.name}"
                }
                // The registry's automatic preload tolerates unresolved references. Explicit initialization must not.
                schema.initializeValidators()
                tool.name to schema
            }
    }

    // LFM3 renders strings without quotes, so `123` can denote a string or a number.
    // Resolve that ambiguity against the compiled whole schema, including refs,
    // unions, conditional properties, and additionalProperties. Never implement
    // a second partial JSON Schema interpreter in the wire parser.
    fun decodeTextArguments(
        name: String,
        arguments: Map<String, String>,
    ): JsonObject {
        val literal = JsonObject(arguments.mapValues { JsonPrimitive(it.value) })
        val schema = schemas[name] ?: return literal

        fun valid(value: JsonObject): Boolean = schema.validate(value.toString(), InputFormat.JSON).isEmpty()
        if (valid(literal)) return literal
        val typed =
            arguments.mapValues { (_, value) ->
                try {
                    // Use the same strict JSON reader as validation. Kotlin's
                    // tree parser accepts leading-zero numbers such as 00123.
                    mapper.readTree(value)?.let { Json.parseToJsonElement(it.toString()) } ?: JsonPrimitive(value)
                } catch (_: JacksonException) {
                    JsonPrimitive(value)
                }
            }
        val alternatives = typed.keys.filter { typed[it] != literal[it] }
        var candidate = JsonObject(typed)
        // Usually all JSON values already satisfy the schema. For mixed literal
        // strings and scalars, search bounded alternatives. Failure still goes
        // through ordinary schema validation and yields a corrective tool result.
        if (!valid(candidate)) {
            var remaining = 256

            fun search(
                index: Int,
                values: Map<String, JsonElement>,
            ): JsonObject? {
                if (remaining-- <= 0) return null
                val value = JsonObject(values)
                if (valid(value)) return value
                for (next in index until alternatives.size) {
                    val key = alternatives[next]
                    search(next + 1, values + (key to checkNotNull(literal[key])))?.let { return it }
                }
                return null
            }
            candidate = search(0, typed) ?: return candidate
        }
        // Prefer verbatim string values whenever both interpretations are legal.
        for (key in alternatives) {
            val reverted = JsonObject(candidate + (key to checkNotNull(literal[key])))
            if (valid(reverted)) candidate = reverted
        }
        return candidate
    }

    fun validate(
        name: String,
        arguments: JsonObject,
    ): String? {
        val schema = schemas[name] ?: return "Unknown tool. Use an advertised tool name."
        val errors = schema.validate(arguments.toString(), InputFormat.JSON)
        if (errors.isEmpty()) return null
        val summary = errors.take(3).joinToString("; ") { "${it.instanceLocation}: ${it.message}".take(300) }
        return "Invalid arguments: $summary. Correct the call using its schema."
    }
}
