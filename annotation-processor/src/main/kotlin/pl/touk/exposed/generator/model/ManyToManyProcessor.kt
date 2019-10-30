package pl.touk.exposed.generator.model

import pl.touk.exposed.generator.env.AnnotationEnvironment
import pl.touk.exposed.generator.env.TypeEnvironment
import pl.touk.exposed.generator.env.toTypeElement
import javax.persistence.JoinTable

class ManyToManyProcessor(override val typeEnv: TypeEnvironment, private val annEnv: AnnotationEnvironment) : ElementProcessor {

    override fun process(graphs: EntityGraphs) =
            processElements(annEnv.manyToMany, graphs) { entity, manyToManyElt ->
                val joinTableAnn = manyToManyElt.getAnnotation(JoinTable::class.java)
                val target = manyToManyElt.asType().getTypeArgument().asElement().toTypeElement()
                val targetId = graphs.entityId(target)
                val associationDef = AssociationDefinition(
                        name = manyToManyElt.simpleName, type = AssociationType.MANY_TO_MANY,
                        target = target, joinTable = joinTableAnn.name, targetId = targetId
                )
                entity.addAssociation(associationDef)
            }
}
