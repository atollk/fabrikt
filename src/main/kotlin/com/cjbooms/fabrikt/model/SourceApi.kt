package com.cjbooms.fabrikt.model

import com.beust.jcommander.ParameterException
import com.cjbooms.fabrikt.model.OasType.Companion.toOasType
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isNotDefined
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isOneOfPolymorphicTypes
import com.cjbooms.fabrikt.util.NormalisedString.toModelClassName
import com.cjbooms.fabrikt.util.YamlUtils
import com.cjbooms.fabrikt.validation.ValidationError
import com.reprezen.jsonoverlay.IJsonOverlay
import com.reprezen.jsonoverlay.Overlay
import com.reprezen.kaizen.oasparser.model3.OpenApi3
import com.reprezen.kaizen.oasparser.model3.Schema
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger

data class SchemaInfo(
    val oasKey: String,
    val fullName: String,
    val schema: Schema
) {
    val typeInfo: KotlinTypeInfo = KotlinTypeInfo.from(schema, oasKey = oasKey, fullName = fullName)
}

data class SourceApi(
    private val rawApiSpec: String,
    val baseDir: Path = Paths.get("").toAbsolutePath()
) {
    companion object {
        private val logger = Logger.getGlobal()

        fun create(
            baseApi: String,
            apiFragments: Collection<String>,
            baseDir: Path = Paths.get("").toAbsolutePath()
        ): SourceApi {
            val combinedApi =
                apiFragments.fold(baseApi) { acc: String, fragment -> YamlUtils.mergeYamlTrees(acc, fragment) }
            return SourceApi(combinedApi, baseDir)
        }
    }

    val openApi3: OpenApi3 = YamlUtils.parseOpenApi(rawApiSpec, baseDir)
    val allSchemas: Map<String, SchemaInfo>

    init {
        validateSchemaObjects(openApi3).let {
            if (it.isNotEmpty()) throw ParameterException("Invalid models or api file:\n${it.joinToString("\n\t")}")
        }
        allSchemas = openApi3.schemas.entries.map { it.key to it.value }
            .plus(openApi3.parameters.entries.map { it.key to it.value.schema })
            .plus(openApi3.responses.entries.flatMap { it.value.contentMediaTypes.entries.map { content -> it.key to content.value.schema } })
            .associate { (baseName, schema) ->
                schema.pathFromRoot().joinToString("/") to SchemaInfo(
                    baseName,
                    SchemaNameBuilder.getName(schema),
                    schema
                )
            }
    }

    private fun validateSchemaObjects(api: OpenApi3): List<ValidationError> {
        val schemaErrors = api.schemas.entries.fold(emptyList<ValidationError>()) { errors, entry ->
            val name = entry.key
            val schema = entry.value
            if (schema.type == OasType.Object.type && (
                        schema.oneOfSchemas?.isNotEmpty() == true ||
                                schema.allOfSchemas?.isNotEmpty() == true ||
                                schema.anyOfSchemas?.isNotEmpty() == true
                        )
            )
                errors + listOf(
                    ValidationError(
                        "'$name' schema contains an invalid combination of properties and `oneOf | anyOf | allOf`. " +
                                "Do not use properties and a combiner at the same level."
                    )
                )
            else if (schema.type == OasType.Object.type && schema.oneOfSchemas?.isNotEmpty() == true)
                errors + listOf(ValidationError("The $name object contains invalid use of both properties and `oneOf`."))
            else if (schema.type == OasType.Object.type && schema.oneOfSchemas?.isNotEmpty() == true)
                errors + listOf(ValidationError("The $name object contains invalid use of both properties and `oneOf`."))
            else if (schema.type == null && schema.properties?.isNotEmpty() == true) {
                logger.warning("Schema '$name' has 'type: null' but defines properties. Assuming: 'type: object'")
                errors
            } else errors
        }

        return api.schemas.map { it.value.properties }.flatMap { it.entries }
            .fold(schemaErrors) { lst, entry ->
                val name = entry.key
                val schema = entry.value
                if (schema.type == OasType.Array.type && schema.itemsSchema.isNotDefined()) {
                    lst + listOf(ValidationError("Array type '$name' cannot be parsed to a Schema. Check your input"))
                } else if (schema.isNotDefined()) {
                    lst + listOf(ValidationError("Property '$name' cannot be parsed to a Schema. Check your input"))
                } else {
                    lst
                }
            }
    }
}

fun Schema.fullInfo() = SchemaInfo(SchemaNameBuilder.getOasKey(this), SchemaNameBuilder.getName(this), this)

private fun IJsonOverlay<*>.pathFromRoot(): List<String> = Overlay.of(this).pathFromRoot!!.split("/")

object SchemaNameBuilder {
    fun getOasKey(schema: Schema): String {
        val overlay = Overlay.of(schema)
        return overlay.pathInParent ?: overlay.pathFromRoot.split("/").lastOrNull() ?: ""
    }

    fun getName(schema: Schema): String {
        // TODO: do an actual implementation
        return when (val type = schema.toOasType(getOasKey(schema))) {
            OasType.Object -> schema.toModelClassName()
            OasType.Array -> {
                val path = schema.pathFromRoot()
                val error = UnsupportedOperationException(path.joinToString("/"))
                if (path.size < 3)
                    throw error
                if (path[path.size-2] == "properties")
                    schema.toModelClassName(path[path.size-3])
                else if (path[path.size-2] == "schemas")
                    schema.itemsSchema.fullInfo().fullName
                else
                    throw error
            }
            else -> ""
        }
    }

    private fun Schema.toModelClassName(enclosingClassName: String = "") =
        enclosingClassName + safeName().toModelClassName()

    private val invalidNames =
        listOf(
            "anyOf",
            "oneOf",
            "allOf",
            "items",
            "schema",
            "application~1json",
            "content",
            "additionalProperties",
            "properties",
        )

    private fun Schema.safeName(): String =
        when {
            isOneOfPolymorphicTypes() -> this.oneOfSchemas.first().allOfSchemas.first().safeName()
            name != null -> name
            else -> Overlay.of(this).pathFromRoot
                .splitToSequence("/")
                .filterNot { invalidNames.contains(it) }
                .filter { it.toIntOrNull() == null } // Ignore numeric-identifiers path-parts in: allOf / oneOf / anyOf
                .last()
        }
}