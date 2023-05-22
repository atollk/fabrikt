package com.cjbooms.fabrikt.model

import com.cjbooms.fabrikt.util.KaizenParserExtensions.getKeyIfSingleDiscriminatorValue
import com.cjbooms.fabrikt.util.KaizenParserExtensions.hasAdditionalProperties
import com.cjbooms.fabrikt.util.KaizenParserExtensions.hasNoDiscriminator
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isDiscriminatorProperty
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isInLinedObjectUnderAllOf
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isInlinedArrayDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isInlinedEnumDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isInlinedObjectDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isRequired
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isSchemaLess
import com.cjbooms.fabrikt.util.KaizenParserExtensions.isSimpleMapDefinition
import com.cjbooms.fabrikt.util.KaizenParserExtensions.safeType
import com.cjbooms.fabrikt.util.NormalisedString.camelCase
import com.cjbooms.fabrikt.util.NormalisedString.toEnumName
import com.reprezen.kaizen.oasparser.model3.Schema

sealed class PropertyInfo {
    abstract val oasKey: String
    abstract val typeInfo: KotlinTypeInfo
    abstract val schema: Schema
    open val isRequired: Boolean = false
    open val isInherited: Boolean = false

    val name: String
        get() = oasKey.camelCase()

    companion object {

        data class Settings(
            val markAsInherited: Boolean = false,
            val markReadWriteOnlyOptional: Boolean = true,
            val markAllOptional: Boolean = false,
            val excludeWriteOnly: Boolean = false
        )

        val HTTP_SETTINGS = Settings()

        fun SchemaInfo.topLevelProperties(settings: Settings, enclosingSchema: SchemaInfo? = null): Collection<PropertyInfo> {
            val results = mutableListOf<PropertyInfo>() +
                schema.allOfSchemas.flatMap {
                    it.fullInfo().topLevelProperties(
                        maybeMarkInherited(
                            settings,
                            enclosingSchema?.schema,
                            it.fullInfo()
                        ),
                        this
                    )
                } +
                (if (schema.oneOfSchemas.isEmpty()) emptyList() else listOf(OneOfAny(schema.oneOfSchemas.first()))) +
                schema.anyOfSchemas.flatMap { it.fullInfo().topLevelProperties(settings.copy(markAllOptional = true), this) } +
                getInLinedProperties(settings, enclosingSchema)
            return results.distinctBy { it.oasKey }
        }

        private fun maybeMarkInherited(settings: Settings, enclosingSchema: Schema?, schemaInfo: SchemaInfo): Settings {
            val isInherited = when {
                schemaInfo.fullName == enclosingSchema?.name -> false
                schemaInfo.schema.hasNoDiscriminator() -> settings.markAsInherited
                schemaInfo.schema.isInLinedObjectUnderAllOf() && schemaInfo.schema.hasNoDiscriminator() -> settings.markAsInherited
                else -> true
            }
            return settings.copy(markAsInherited = isInherited)
        }

        private fun SchemaInfo.getInLinedProperties(
            settings: Settings,
            enclosingSchema: SchemaInfo? = null
        ): Collection<PropertyInfo> {
            val mainProperties: List<PropertyInfo> = this.schema.properties.map { property ->
                when (property.value.safeType()) {
                    OasType.Array.type ->
                        ListField(
                            schema.isRequired(property, settings.markReadWriteOnlyOptional, settings.markAllOptional),
                            property.key,
                            property.value,
                            settings.markAsInherited,
                            this,
                            if (property.value.isInlinedArrayDefinition() || property.value.itemsSchema.isInlinedEnumDefinition())
                                enclosingSchema
                            else null
                        )
                    OasType.Object.type ->
                        if (property.value.isSimpleMapDefinition() || property.value.isSchemaLess())
                            MapField(
                                isRequired = schema.isRequired(
                                    property,
                                    settings.markReadWriteOnlyOptional,
                                    settings.markAllOptional
                                ),
                                oasKey = property.key,
                                schema = property.value,
                                isInherited = settings.markAsInherited,
                                parentSchema = this
                            )
                        else if (property.value.isInlinedObjectDefinition())
                            ObjectInlinedField(
                                isRequired = schema.isRequired(
                                    property, settings.markReadWriteOnlyOptional, settings.markAllOptional
                                ),
                                oasKey = property.key,
                                schema = property.value,
                                isInherited = settings.markAsInherited,
                                parentSchema = this,
                                enclosingSchema = enclosingSchema
                            )
                        else
                            ObjectRefField(
                                schema.isRequired(property, settings.markReadWriteOnlyOptional, settings.markAllOptional),
                                property.key,
                                property.value,
                                settings.markAsInherited,
                                this
                            )
                    else ->
                        if (property.value.isWriteOnly && settings.excludeWriteOnly) {
                            null
                        } else {
                            Field(
                                schema.isRequired(property, settings.markReadWriteOnlyOptional, settings.markAllOptional),
                                oasKey = property.key,
                                schema = property.value,
                                isInherited = settings.markAsInherited,
                                isPolymorphicDiscriminator = schema.isDiscriminatorProperty(property),
                                maybeDiscriminator = enclosingSchema?.let {
                                    this.schema.getKeyIfSingleDiscriminatorValue(property, it.schema)
                                },
                                enclosingSchema = if (property.value.isInlinedEnumDefinition()) this else null
                            )
                        }
                }
            }.filterNotNull()

            return if (schema.hasAdditionalProperties())
                mainProperties
                    .plus(
                        AdditionalProperties(schema.additionalPropertiesSchema, settings.markAsInherited, this)
                    )
            else mainProperties
        }
    }

    sealed class DiscriminatorKey(val stringValue: String, val modelName: String) {
        class StringKey(value: String, modelName: String) : DiscriminatorKey(value, modelName)
        class EnumKey(value: String, modelName: String) : DiscriminatorKey(value, modelName) {
            val enumKey = value.toEnumName()
        }
    }

    data class Field(
        override val isRequired: Boolean,
        override val oasKey: String,
        override val schema: Schema,
        override val isInherited: Boolean,
        val isPolymorphicDiscriminator: Boolean,
        val maybeDiscriminator: Map<String, DiscriminatorKey>?,
        val enclosingSchema: SchemaInfo? = null
    ) : PropertyInfo() {
        override val typeInfo: KotlinTypeInfo = schema.fullInfo().typeInfo
        val pattern: String? = schema.safeField(Schema::getPattern)
        val maxLength: Int? = schema.safeField(Schema::getMaxLength)
        val minLength: Int? = schema.safeField(Schema::getMinLength)
        val minimum: Number? = schema.safeField(Schema::getMinimum)
        val exclusiveMinimum: Boolean? = schema.safeField(Schema::getExclusiveMinimum)
        val maximum: Number? = schema.safeField(Schema::getMaximum)
        val exclusiveMaximum: Boolean? = schema.safeField(Schema::getExclusiveMaximum)

        private fun <T> Schema.safeField(getField: Schema.() -> T?): T? = this.getField()
    }

    interface CollectionValidation {
        val minItems: Int?
        val maxItems: Int?
    }

    data class ListField(
        override val isRequired: Boolean,
        override val oasKey: String,
        override val schema: Schema,
        override val isInherited: Boolean,
        val parentSchema: SchemaInfo,
        val enclosingSchema: SchemaInfo?
    ) : PropertyInfo(), CollectionValidation {
        override val typeInfo: KotlinTypeInfo = if (enclosingSchema == null)
            KotlinTypeInfo.Array(schema.itemsSchema.fullInfo().typeInfo)
        else
            schema.fullInfo().typeInfo
        override val minItems: Int? = schema.minItems
        override val maxItems: Int? = schema.maxItems
    }

    data class MapField(
        override val isRequired: Boolean,
        override val oasKey: String,
        override val schema: Schema,
        override val isInherited: Boolean,
        val parentSchema: SchemaInfo
    ) : PropertyInfo() {
        override val typeInfo: KotlinTypeInfo = schema.fullInfo().typeInfo
    }

    data class ObjectRefField(
        override val isRequired: Boolean,
        override val oasKey: String,
        override val schema: Schema,
        override val isInherited: Boolean,
        val parentSchema: SchemaInfo
    ) : PropertyInfo() {
        override val typeInfo: KotlinTypeInfo = schema.fullInfo().typeInfo
    }

    data class ObjectInlinedField(
        override val isRequired: Boolean,
        override val oasKey: String,
        override val schema: Schema,
        override val isInherited: Boolean,
        val parentSchema: SchemaInfo,
        val enclosingSchema: SchemaInfo?
    ) : PropertyInfo() {
        override val typeInfo: KotlinTypeInfo = schema.fullInfo().typeInfo
    }

    data class AdditionalProperties(
        override val schema: Schema,
        override val isInherited: Boolean,
        val parentSchema: SchemaInfo
    ) : PropertyInfo() {
        override val oasKey: String = "properties"
        override val typeInfo: KotlinTypeInfo = KotlinTypeInfo.from(schema, "additionalProperties", "TODOADDITIONALPROPERTIES")
        override val isRequired: Boolean = true
    }

    data class OneOfAny(
        override val schema: Schema,
    ) : PropertyInfo() {
        override val oasKey: String = "oneOf"
        override val typeInfo: KotlinTypeInfo = KotlinTypeInfo.AnyType
    }
}
