// Frontend operations that create a visualization from a project.
package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.graph_api.Scalar
import com.lynxanalytics.biggraph.graph_api.Scripting._
import play.api.libs.json

class VisualizationOperations(env: SparkFreeEnvironment) extends OperationRegistry {
  implicit lazy val manager = env.metaGraphManager
  import Operation.Category
  import Operation.Context
  import OperationParams._

  val VisualizationOperations = Category("Visualization operations", "blue")

  // Takes a Project as input and returns a Visualization as output.
  registerOp(
    "Create visualization",
    "black_question_mark_ornament",
    VisualizationOperations,
    List("project"),
    List("visualization"),
    new SimpleOperation(_) {

      protected lazy val project = context.inputs("project").project

      override def getOutputs(): Map[BoxOutput, BoxOutputState] = {
        params.validate()
        Map(
          context.box.output(context.meta.outputs(0)) ->
            BoxOutputState.visualization(
              VisualizationState.fromString(
                params("state"),
                project)))
      }

      override val params = new ParameterHolder(context) // No "apply_to" parameters.
      import UIStatusSerialization._
      params += VisualizationParam(
        "state",
        "Left-side and right-side UI statuses as JSON",
        json.Json.toJson(TwoSidedUIStatus(
          left = Some(UIStatus.default.copy(
            projectPath = Some(""),
            graphMode = Some("sampled"))),
          right = None)).toString)
    })

}
