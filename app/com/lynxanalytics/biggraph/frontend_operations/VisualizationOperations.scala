// Frontend operations that create a plot from a table.
package com.lynxanalytics.biggraph.frontend_operations

import com.lynxanalytics.biggraph.SparkFreeEnvironment
import com.lynxanalytics.biggraph.graph_operations
import com.lynxanalytics.biggraph.controllers._
import com.lynxanalytics.biggraph.graph_api.Scalar
import com.lynxanalytics.biggraph.graph_api.Scripting._

class VisualizationOperations(env: SparkFreeEnvironment) extends OperationRegistry {
  implicit lazy val manager = env.metaGraphManager
  import Operation.Category
  import Operation.Context
  import OperationParams._

  val VisualizationOperations = Category("Visuzalization operations", "lightblue", icon = "eye")

  def register(id: String, factory: Context => Operation): Unit = {
    registerOp(id, VisualizationOperations, List("project"), List("visualization"), factory)
  }

  // A VisualizationOperation takes a Table as input and returns a Plot as output.
  class VisualizationOperation(val context: Operation.Context) extends BasicOperation {
    assert(
      context.meta.inputs == List("project"),
      s"A VisualizationOperation must input a single project. $context")
    assert(
      context.meta.outputs == List("visualization"),
      s"A PlotOperation must output a Plot. $context"
    )

    protected lazy val project = projectInput("project")

    def apply() = ???

    def getOutputs(): Map[BoxOutput, BoxOutputState] = {
      validateParameters(params)

      Map(
        context.box.output(context.meta.outputs(0)) ->
          BoxOutputState.visualization(
            project,
            params("leftStateJson"),
            params("rightStateJson"))
      )
    }

    def enabled = FEStatus.enabled

    lazy val parameters = List(
      Param(
        "leftStateJson",
        "Left-side UI status as JSON"),
      Param(
        "rightStateJson",
        "Right-side UI status as JSON"))

  }

  register("Create visualization", new VisualizationOperation(_))
}
