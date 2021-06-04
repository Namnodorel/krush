package pl.touk.krush.source

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import pl.touk.krush.model.*
import pl.touk.krush.model.AssociationType.*
import pl.touk.krush.validation.EntityNotMappedException
import javax.lang.model.element.TypeElement

@KotlinPoetMetadataPreview
class RealReferencesMappingsGenerator : MappingsGenerator() {

    override fun buildToEntityMapFuncBody(
        entityType: TypeElement,
        entity: EntityDefinition,
        graphs: EntityGraphs,
        func: FunSpec.Builder,
        entityId: IdDefinition,
        rootKey: TypeName,
        rootVal: String,
        rootIdName: String,
        rootValId: String
    ): FunSpec {

        // Map with each entity, identified by its key (usually an Int id)
        func.addStatement("var roots = mutableMapOf<$rootKey, ${entity.name}>()")

        // Initialize maps storing associated data
        val associations = entity.getAssociations(ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY)
        associations.forEach { assoc ->
            val target = graphs[assoc.target.packageName]?.get(assoc.target) ?: throw EntityNotMappedException(assoc.target)
            val entityIdTypeName = entityId.asUnderlyingTypeName()
            val associationMapName = "${entity.name.asVariable()}_${assoc.name}"
            val associationMapValueType = if (assoc.type in listOf(ONE_TO_MANY, MANY_TO_MANY)) "MutableSet<${target.name}>" else "${target.name}"

            func.addStatement("val $associationMapName = mutableMapOf<${entityIdTypeName}, $associationMapValueType>()")
            if (!(assoc.type == ONE_TO_ONE && assoc.mapped)) {
                val isSelfReferential = assoc.target == entityType

                // Prevent infinite recursions
                // (when the table is self-referential, roots will be used as "foreign map" - see below)
                if (!isSelfReferential) {
                    func.addStatement("val ${assoc.name}_map = this.to${assoc.target.simpleName}Map()")
                }
            }
        }

        // Add all non-relational data to the roots map
        func.addStatement("this.forEach { resultRow ->")
        func.addStatement("\tval $rootValId = resultRow.getOrNull(${entity.name}Table.${entityId.name}) ?: return@forEach")
        func.addStatement("\tval $rootVal = roots[$rootValId] ?: resultRow.to${entity.name}()")

        // Add data from One-To-One relations
        var relationCopyBlock = ""
        associations.forEach { assoc ->
            if(assoc.type != ONE_TO_ONE) {
                return@forEach
            }

            val target = graphs[assoc.target.packageName]?.get(assoc.target) ?: throw EntityNotMappedException(assoc.target)
            val assocVar = assoc.name.asVariable()

            if(assoc.nullable) {
                // If the target ID is present, get the target object. Otherwise, set it to null.
                func.addStatement("\tval $assocVar: ${target.name}?")
                func.addStatement("\tval ${assocVar}Id = resultRow.getOrNull(${target.idColumn})")
                func.addStatement("\tif(${assocVar}Id != null) {")
                func.addStatement("\t\t$assocVar = resultRow.to${target.name}()")
                func.addStatement("\t} else {")
                func.addStatement("\t\t$assocVar = null")
                func.addStatement("\t}")
            } else {
                func.addStatement("\tval $assocVar = resultRow.to${target.name}()")
            }

            // Add a line for the copy() function that adds this relation to the entity
            relationCopyBlock += "$assocVar = $assocVar,\n"
        }

        if(relationCopyBlock.isNotEmpty()) {
            func.addStatement("\troots[$rootValId] = ${rootVal}.copy(\n$relationCopyBlock)")
        } else {
            func.addStatement("\troots[$rootValId] = $rootVal")
        }

        func.addStatement("}")

        // Add O2M and M2M relations
        if (associations.any { it.type == ONE_TO_MANY || it.type == MANY_TO_MANY }) {

            // Add list relational data (this is done in a separate step so that self-referential relations work)
            func.addStatement("this.forEach { resultRow ->")
            func.addStatement("\tval $rootValId = resultRow.getOrNull(${entity.name}Table.${entityId.name}) ?: return@forEach")
            func.addStatement("\tval $rootVal = roots[$rootValId] ?: resultRow.to${entity.name}()")
            associations.forEach { assoc ->
                val target = graphs[assoc.target.packageName]?.get(assoc.target) ?: throw EntityNotMappedException(assoc.target)
                val targetVal = target.name.asVariable()
                val collName = "${assoc.name}_$rootVal"
                val associationMapName = "${entity.name.asVariable()}_${assoc.name}"

                // Use self as "foreign" table if the table is self-referential
                val isSelfReferential = assoc.target == entityType

                val foreignTableMap: String
                if (!isSelfReferential) {
                    foreignTableMap = "${assoc.name}_map"
                } else {
                    foreignTableMap = "roots"
                }

                when (assoc.type) {
                    ONE_TO_MANY -> {
                        func.addStatement("\tresultRow.getOrNull(${target.idColumn})?.let {")
                        func.addStatement("\t\tval $collName = $foreignTableMap.filter { $targetVal -> $targetVal.key == it }")

                        val isBidirectional = target.associations.find { it.target == entityType }?.mapped ?: false
                        if (isBidirectional) {
                            func.addStatement("\t\t\t.mapValues { (_, $targetVal) -> $targetVal.copy($rootVal = $rootVal) }")
                        }

                        func.addStatement("\t\t\t.values.toMutableSet()")
                        func.addStatement("\t\t$associationMapName[$rootValId]?.addAll($collName) ?: $associationMapName.put($rootValId, $collName)")
                        func.addStatement("\t}")
                    }

                    MANY_TO_MANY -> {
                        val assocTableTargetIdCol = "${entity.name}${assoc.name.asObject()}Table.${targetVal}TargetId"

                        func.addStatement("\tresultRow.getOrNull($assocTableTargetIdCol)?.let {")
                        func.addStatement("\t\tval $collName = $foreignTableMap.filter { $targetVal -> $targetVal.key == it }")

                        func.addStatement("\t\t\t.values.toMutableSet()")
                        func.addStatement("\t\t$associationMapName[$rootValId]?.addAll($collName) ?: $associationMapName.put($rootValId, $collName)")
                        func.addStatement("\t}")
                    }

                    else -> {}
                }
            }
            func.addStatement("}")

            func.addStatement("roots.forEach { (_, $rootVal) ->")
            associations.forEach { assoc ->
                if (assoc.type !in listOf(ONE_TO_MANY, MANY_TO_MANY)) {
                    return@forEach
                }

                val associationMapName = "${entity.name.asVariable()}_${assoc.name}"
                func.addStatement("\t\tval ${associationMapName}_relations = $associationMapName[$rootVal.$rootIdName]?.toList()")
                func.addStatement("\t\tif (${associationMapName}_relations != null) {")
                func.addStatement("\t\t\t($rootVal.${assoc.name} as MutableList).addAll(${associationMapName}_relations)")
                func.addStatement("\t\t}")
            }
            func.addStatement("\t}")
        }

        func.addStatement("return roots")

        return func.build()
    }

}
