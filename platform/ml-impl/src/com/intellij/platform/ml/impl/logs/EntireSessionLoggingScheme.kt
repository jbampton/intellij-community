// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ml.impl.logs

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.*
import com.intellij.platform.ml.Feature
import com.intellij.platform.ml.FeatureDeclaration
import com.intellij.platform.ml.PerTier
import com.intellij.platform.ml.TierInstance
import com.intellij.platform.ml.impl.session.*
import org.jetbrains.annotations.ApiStatus

/**
 * The logging scheme that is logging entire session into one event.
 *
 * If your sessions of your task are large enough, then it's possible that they
 * won't fit into one event, as it has a limit.
 * In such a case try using [com.intellij.platform.ml.impl.logs.SessionAsMultipleEventsLoggingScheme].
 */
@ApiStatus.Internal
class EntireSessionLoggingScheme<P : Any, F>(
  private val predictionField: EventField<F>,
  private val predictionTransformer: (P?) -> F?,
) : MLSessionScheme<P> {
  override fun configureLogger(sessionAnalysisDeclaration: List<EventField<*>>,
                               sessionStructureAnalysisDeclaration: List<AnalysedLevelScheme>,
                               eventLogGroup: EventLogGroup,
                               eventPrefix: String): MLSessionLoggerBuilder<P> {
    require(sessionStructureAnalysisDeclaration.isNotEmpty())
    val sessionStructureFields = if (sessionStructureAnalysisDeclaration.size == 1)
      PredictionSessionFields(sessionStructureAnalysisDeclaration.first(), predictionField, predictionTransformer)
    else
      NestableSessionFields(sessionStructureAnalysisDeclaration.first(), sessionStructureAnalysisDeclaration.drop(1), predictionField,
                            predictionTransformer)

    val fieldSessionStructure = ObjectEventField("structure", sessionStructureFields)
    val fieldSession = ObjectEventField("session", *sessionAnalysisDeclaration.toTypedArray())

    val eventId = eventLogGroup.registerVarargEvent(eventPrefix,
                                                    fieldSessionStructure,
                                                    fieldSession)

    return MLSessionLoggerBuilder {
      var bufferAnalysisSessionStructure: ObjectEventData? = null
      val bufferAnalysisSession: MutableList<EventPair<*>> = mutableListOf()
      var logged = false

      fun logEvent() {
        assert(!logged) { "Attempted to log session twice" }
        eventId.log(listOfNotNull(
          bufferAnalysisSessionStructure?.let { fieldSessionStructure with it },
          fieldSession with ObjectEventData(bufferAnalysisSession)
        ))
        logged = true
      }

      object : MLSessionLogger<P> {
        override fun logBeforeSessionStarted(startedSessionAnalysis: List<EventPair<*>>) {
          bufferAnalysisSession.addAll(startedSessionAnalysis)
        }

        override fun logStartFailure(failureAnalysis: List<EventPair<*>>) {
          bufferAnalysisSession.addAll(failureAnalysis)
          logEvent()
        }

        override fun logSessionException(exceptionAnalysis: List<EventPair<*>>) {
          bufferAnalysisSession.addAll(exceptionAnalysis)
          logEvent()
        }

        override fun logStarted(startAnalysis: List<EventPair<*>>) {
          bufferAnalysisSession.addAll(startAnalysis)
        }

        override fun logFinished(sessionStructure: AnalysedRootContainer<P>, finishedSessionAnalysis: List<EventPair<*>>) {
          bufferAnalysisSessionStructure = sessionStructureFields.buildObjectEventData(sessionStructure)
          bufferAnalysisSession.addAll(finishedSessionAnalysis)
          logEvent()
        }
      }
    }
  }

  companion object {
    val DOUBLE: MLSessionScheme<Double> = EntireSessionLoggingScheme(DoubleEventField("prediction")) { it }
    val UNIT: MLSessionScheme<Unit> = EntireSessionLoggingScheme(BooleanEventField("prediction")) { null }
  }
}

private abstract class SessionFields<P : Any> : ObjectDescription() {
  fun buildObjectEventData(sessionStructure: AnalysedSessionTree<P>) = ObjectEventData(buildEventPairs(sessionStructure))

  abstract fun buildEventPairs(sessionStructure: AnalysedSessionTree<P>): List<EventPair<*>>
}

private data class AdditionalTierFields(val description: TierDescriptionFields) : ObjectDescription() {
  private val fieldInstanceId = IntEventField("id")
  private val fieldDescription = ObjectEventField("description", description)

  constructor(descriptionFeatures: Set<FeatureDeclaration<*>>)
    : this(TierDescriptionFields(used = FeatureSet(descriptionFeatures),
                                 notUsed = FeatureSet(descriptionFeatures)))

  init {
    field(fieldInstanceId)
    field(fieldDescription)
  }

  fun buildObjectEventData(tierInstance: TierInstance<*>,
                           descriptionPartition: DescriptionPartition) = ObjectEventData(
    fieldInstanceId with tierInstance.instance.hashCode(),
    fieldDescription with this.description.buildObjectEventData(descriptionPartition),
  )
}

private data class MainTierFields(
  val description: TierDescriptionFields,
  val analysis: FeatureSet,
) : ObjectDescription() {
  private val fieldInstanceId = IntEventField("id")
  private val fieldDescription = ObjectEventField("description", description)
  private val fieldAnalysis = ObjectEventField("analysis", analysis)

  constructor(descriptionFeatures: Set<FeatureDeclaration<*>>, analysisFeatures: Set<FeatureDeclaration<*>>)
    : this(TierDescriptionFields(used = FeatureSet(descriptionFeatures),
                                 notUsed = FeatureSet(descriptionFeatures)),
           FeatureSet(analysisFeatures))

  init {
    field(fieldInstanceId)
    field(fieldDescription)
    field(fieldAnalysis)
  }

  fun buildObjectEventData(tierInstance: TierInstance<*>,
                           descriptionPartition: DescriptionPartition,
                           analysis: Set<Feature>) = ObjectEventData(
    fieldInstanceId with tierInstance.instance.hashCode(),
    fieldDescription with this.description.buildObjectEventData(descriptionPartition),
    fieldAnalysis with this.analysis.toObjectEventData(analysis)
  )
}

private class MainTierSet<P : Any>(mainTierScheme: PerTier<AnalysedTierScheme>) : SessionFields<P>() {
  val tiersDeclarations: PerTier<MainTierFields> = mainTierScheme.entries.associate { (tier, tierScheme) ->
    tier to MainTierFields(tierScheme.description, tierScheme.analysis)
  }
  val fieldPerTier: PerTier<ObjectEventField> = tiersDeclarations.entries.associate { (tier, tierFields) ->
    tier to ObjectEventField(tier.name, tierFields)
  }

  init {
    fieldPerTier.values.forEach { field(it) }
  }

  override fun buildEventPairs(sessionStructure: AnalysedSessionTree<P>): List<EventPair<*>> {
    val level = sessionStructure.levelData.mainInstances
    return level.entries.map { (tierInstance, data) ->
      val tierField = requireNotNull(fieldPerTier[tierInstance.tier]) {
        "Tier ${tierInstance.tier} is now allowed here: only ${fieldPerTier.keys} are registered"
      }
      val tierDeclaration = tiersDeclarations.getValue(tierInstance.tier)
      tierField with tierDeclaration.buildObjectEventData(tierInstance, data.description, data.analysis)
    }
  }
}

private class AdditionalTierSet<P : Any>(additionalTierScheme: PerTier<DescribedTierScheme>) : SessionFields<P>() {
  val tiersDeclarations: PerTier<AdditionalTierFields> = additionalTierScheme.entries.associate { (tier, tierScheme) ->
    tier to AdditionalTierFields(tierScheme.description)
  }
  val fieldPerTier: PerTier<ObjectEventField> = tiersDeclarations.entries.associate { (tier, tierFields) ->
    tier to ObjectEventField(tier.name, tierFields)
  }

  init {
    fieldPerTier.values.forEach { field(it) }
  }

  override fun buildEventPairs(sessionStructure: AnalysedSessionTree<P>): List<EventPair<*>> {
    val level = sessionStructure.levelData.additionalInstances
    return level.entries.map { (tierInstance, data) ->
      val tierField = requireNotNull(fieldPerTier[tierInstance.tier]) {
        "Tier ${tierInstance.tier} is now allowed here: only ${fieldPerTier.keys} are registered"
      }
      val tierDeclaration = tiersDeclarations.getValue(tierInstance.tier)
      tierField with tierDeclaration.buildObjectEventData(tierInstance, data.description)
    }
  }
}

private data class PredictionSessionFields<P : Any, F>(
  val declarationMainTierSet: MainTierSet<P>,
  val declarationAdditionalTierSet: AdditionalTierSet<P>,
  val fieldPrediction: EventField<F>,
  val serializePrediction: (P?) -> F?,
) : SessionFields<P>() {
  private val fieldMainInstances = ObjectEventField("main", declarationMainTierSet)
  private val fieldAdditionalInstances = ObjectEventField("additional", declarationAdditionalTierSet)

  constructor(levelScheme: AnalysedLevelScheme,
              fieldPrediction: EventField<F>,
              serializePrediction: (P?) -> F?)
    : this(MainTierSet(levelScheme.main),
           AdditionalTierSet(levelScheme.additional),
           fieldPrediction,
           serializePrediction)

  init {
    field(fieldMainInstances)
    field(fieldAdditionalInstances)
    field(fieldPrediction)
  }

  override fun buildEventPairs(sessionStructure: AnalysedSessionTree<P>): List<EventPair<*>> {
    require(sessionStructure is SessionTree.PredictionContainer<*, *, P>)
    val eventPairs = mutableListOf<EventPair<*>>(
      fieldMainInstances with declarationMainTierSet.buildObjectEventData(sessionStructure),
      fieldAdditionalInstances with declarationAdditionalTierSet.buildObjectEventData(sessionStructure),
    )

    val serializedPrediction = serializePrediction(sessionStructure.prediction)
    serializedPrediction?.let {
      eventPairs += fieldPrediction with it
    }

    return eventPairs
  }
}

private data class NestableSessionFields<P : Any, F>(
  val declarationMainTierSet: MainTierSet<P>,
  val declarationAdditionalTierSet: AdditionalTierSet<P>,
  val declarationNestedSession: SessionFields<P>,
) : SessionFields<P>() {
  private val fieldMainInstances = ObjectEventField("main", declarationMainTierSet)
  private val fieldAdditionalInstances = ObjectEventField("additional", declarationAdditionalTierSet)
  private val fieldNestedSessions = ObjectListEventField("nested", declarationNestedSession)

  constructor(levelScheme: AnalysedLevelScheme,
              deeperLevelsSchemes: List<AnalysedLevelScheme>,
              predictionField: EventField<F>,
              serializePrediction: (P?) -> F?)
    : this(MainTierSet(levelScheme.main),
           AdditionalTierSet(levelScheme.additional),
           if (deeperLevelsSchemes.size == 1)
             PredictionSessionFields(deeperLevelsSchemes.first(), predictionField, serializePrediction)
           else {
             require(deeperLevelsSchemes.size > 1)
             NestableSessionFields(deeperLevelsSchemes.first(), deeperLevelsSchemes.drop(1),
                                   predictionField, serializePrediction)
           }
  )

  init {
    field(fieldMainInstances)
    field(fieldAdditionalInstances)
    field(fieldNestedSessions)
    field(fieldNestedSessions)
  }

  override fun buildEventPairs(sessionStructure: AnalysedSessionTree<P>): List<EventPair<*>> {
    require(sessionStructure is SessionTree.ChildrenContainer)
    val children = sessionStructure.children
    val eventPairs = mutableListOf<EventPair<*>>(
      fieldMainInstances with declarationMainTierSet.buildObjectEventData(sessionStructure),
      fieldAdditionalInstances with declarationAdditionalTierSet.buildObjectEventData(sessionStructure),
      fieldNestedSessions with children.map { nestedSession -> declarationNestedSession.buildObjectEventData(nestedSession) }
    )

    return eventPairs
  }
}
