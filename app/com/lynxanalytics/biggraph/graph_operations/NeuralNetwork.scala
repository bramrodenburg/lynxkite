// Trains a neural network on a graph and uses it to predict an attribute.
package com.lynxanalytics.biggraph.graph_operations

import breeze.linalg._
import breeze.stats.distributions.RandBasis

import com.lynxanalytics.biggraph.graph_api._
import com.lynxanalytics.biggraph.spark_util.SortedRDD
import com.lynxanalytics.biggraph.spark_util.Implicits._

object NeuralNetwork extends OpFromJson {
  class Input(featureCount: Int) extends MagicInputSignature {
    val vertices = vertexSet
    val edges = edgeBundle(vertices, vertices)
    val label = vertexAttribute[Double](vertices)
    val features = (0 until featureCount).map(
      i => vertexAttribute[Double](vertices, Symbol("feature-" + i)))
  }
  class Output(implicit instance: MetaGraphOperationInstance,
               inputs: Input) extends MagicOutput(instance) {
    val prediction = vertexAttribute[Double](inputs.vertices.entity)
  }
  def fromJson(j: JsValue) = NeuralNetwork(
    (j \ "featureCount").as[Int],
    (j \ "networkSize").as[Int],
    (j \ "iterations").as[Int],
    (j \ "learningRate").as[Double],
    (j \ "radius").as[Int],
    (j \ "hideState").as[Boolean],
    (j \ "forgetFraction").as[Double])
}
import NeuralNetwork._
case class NeuralNetwork(
    featureCount: Int,
    networkSize: Int,
    iterations: Int,
    learningRate: Double,
    radius: Int,
    hideState: Boolean,
    forgetFraction: Double) extends TypedMetaGraphOp[Input, Output] {
  @transient override lazy val inputs = new Input(featureCount)
  def outputMeta(instance: MetaGraphOperationInstance) = new Output()(instance, inputs)
  override def toJson = Json.obj(
    "featureCount" -> featureCount,
    "networkSize" -> networkSize,
    "iterations" -> iterations,
    "learningRate" -> learningRate,
    "radius" -> radius,
    "hideState" -> hideState,
    "forgetFraction" -> forgetFraction)

  def execute(inputDatas: DataSet,
              o: Output,
              output: OutputBuilder,
              rc: RuntimeContext): Unit = {
    implicit val id = inputDatas

    val edges = CompactUndirectedGraph(rc, inputs.edges.rdd, needsBothDirections = false)
    val reversed = {
      val rdd = inputs.edges.rdd.mapValues { case Edge(src, dst) => Edge(dst, src) }
      CompactUndirectedGraph(rc, rdd, needsBothDirections = false)
    }
    val features = {
      val arrays = inputs.vertices.rdd.mapValues(_ => new Array[Double](featureCount))
      inputs.features.zipWithIndex.foldLeft(arrays) {
        case (arrays, (feature, idx)) =>
          arrays.sortedJoin(feature.rdd).mapValues {
            case (array, feature) =>
              array(idx) = feature
              array
          }
      }
    }
    val labelOpt = inputs.vertices.rdd.sortedLeftOuterJoin(inputs.label.rdd).mapValues(_._2)
    val data: SortedRDD[ID, (Option[Double], Array[Double])] = labelOpt.sortedJoin(features)
    val data1 = data.coalesce(1)
    val prediction = data1.mapPartitions(data => predict(data, edges, reversed))

    output(o.prediction, prediction.sortUnique(inputs.vertices.rdd.partitioner.get))
  }

  type Vector = DenseVector[Double]
  type Matrix = DenseMatrix[Double]

  case class Network(
      size: Int,
      edgeMatrix: Matrix,
      edgeBias: Vector,
      resetInput: Matrix,
      resetHidden: Matrix,
      updateInput: Matrix,
      updateHidden: Matrix,
      activationInput: Matrix,
      activationHidden: Matrix) {
    import breeze.numerics._

    def squared = new Network(
      size,
      edgeMatrix :* edgeMatrix,
      edgeBias :* edgeBias,
      resetInput :* resetInput,
      resetHidden :* resetHidden,
      updateInput :* updateInput,
      updateHidden :* updateHidden,
      activationInput :* activationInput,
      activationHidden :* activationHidden)

    def plus(other: Network) = new Network(
      size,
      edgeMatrix + other.edgeMatrix,
      edgeBias + other.edgeBias,
      resetInput + other.resetInput,
      resetHidden + other.resetHidden,
      updateInput + other.updateInput,
      updateHidden + other.updateHidden,
      activationInput + other.activationInput,
      activationHidden + other.activationHidden)

    def adagradMatrix(memory: Network, gradient: Network, getter: Network => Matrix): Matrix = {
      getter(this) - learningRate * getter(gradient) / sqrt(getter(memory) + 1e-6)
    }
    def adagradVector(memory: Network, gradient: Network, getter: Network => Vector): Vector = {
      getter(this) - learningRate * getter(gradient) / sqrt(getter(memory) + 1e-6)
    }

    def adagrad(memory: Network, gradient: Network) = new Network(
      size,
      adagradMatrix(memory, gradient, _.edgeMatrix),
      adagradVector(memory, gradient, _.edgeBias),
      adagradMatrix(memory, gradient, _.resetInput),
      adagradMatrix(memory, gradient, _.resetHidden),
      adagradMatrix(memory, gradient, _.updateInput),
      adagradMatrix(memory, gradient, _.updateHidden),
      adagradMatrix(memory, gradient, _.activationInput),
      adagradMatrix(memory, gradient, _.activationHidden))
  }
  object Network {

    def zeros(size: Int) = new Network(
      size,
      edgeMatrix = DenseMatrix.zeros[Double](size, size),
      edgeBias = DenseVector.zeros[Double](size),
      resetInput = DenseMatrix.zeros[Double](size, size),
      resetHidden = DenseMatrix.zeros[Double](size, size),
      updateInput = DenseMatrix.zeros[Double](size, size),
      updateHidden = DenseMatrix.zeros[Double](size, size),
      activationInput = DenseMatrix.zeros[Double](size, size),
      activationHidden = DenseMatrix.zeros[Double](size, size))

    def random(size: Int)(implicit r: RandBasis) = {
      val amplitude = 0.01
      new Network(
        size,
        edgeMatrix = randn((size, size)) * amplitude,
        edgeBias = randn(size) * amplitude,
        resetInput = randn((size, size)) * amplitude,
        resetHidden = randn((size, size)) * amplitude,
        updateInput = randn((size, size)) * amplitude,
        updateHidden = randn((size, size)) * amplitude,
        activationInput = randn((size, size)) * amplitude,
        activationHidden = randn((size, size)) * amplitude)
    }
  }

  def vectorSum(size: Int, vectors: Iterable[Vector]) = {
    val sum = DenseVector.zeros[Double](size)
    for (v <- vectors) { sum += v }
    sum
  }

  case class NetworkOutputs(
      network: Network,
      vertices: Iterable[ID],
      edges: CompactUndirectedGraph,
      state: Map[ID, Vector],
      visibleState: Map[ID, Vector]) {
    import breeze.numerics._
    val input: Map[ID, Vector] = vertices.map { id =>
      val inputs = edges.getNeighbors(id).map(network.edgeMatrix * visibleState(_))
      id -> (vectorSum(network.size, inputs) + network.edgeBias)
    }.toMap
    val reset: Map[ID, Vector] = vertices.map { id =>
      id -> sigmoid(network.resetInput * input(id) + network.resetHidden * state(id))
    }.toMap
    val update: Map[ID, Vector] = vertices.map { id =>
      id -> sigmoid(network.updateInput * input(id) + network.updateHidden * state(id))
    }.toMap
    val tildeState: Map[ID, Vector] = vertices.map { id =>
      id -> tanh(network.activationInput * input(id) + network.activationHidden * (reset(id) :* state(id)))
    }.toMap
    val newState: Map[ID, Vector] = vertices.map { id =>
      id -> (((1.0 - update(id)) :* state(id)) + (update(id) :* tildeState(id)))
    }.toMap
  }

  class NetworkGradients(
      network: Network,
      vertices: Iterable[ID],
      edges: CompactUndirectedGraph,
      stateGradient: Map[ID, Vector],
      outputs: NetworkOutputs) {
    import breeze.numerics._
    val tildeGradient: Map[ID, Vector] = vertices.map { id =>
      id -> (outputs.update(id) :* stateGradient(id))
    }.toMap
    val tildeRawGradient: Map[ID, Vector] = vertices.map { id =>
      // Propagate through tanh.
      id -> ((1.0 - (outputs.tildeState(id) :* outputs.tildeState(id))) :* tildeGradient(id))
    }.toMap
    val updateGradient: Map[ID, Vector] = vertices.map { id =>
      id -> ((outputs.tildeState(id) - outputs.state(id)) :* stateGradient(id))
    }.toMap
    val updateRawGradient: Map[ID, Vector] = vertices.map { id =>
      // Propagate through sigmoid.
      id -> (outputs.update(id) :* (1.0 - outputs.update(id)) :* updateGradient(id))
    }.toMap
    val resetGradient: Map[ID, Vector] = vertices.map { id =>
      id -> ((network.activationHidden.t * tildeRawGradient(id)) :* outputs.state(id))
    }.toMap
    val resetRawGradient: Map[ID, Vector] = vertices.map { id =>
      // Propagate through sigmoid.
      id -> (outputs.reset(id) :* (1.0 - outputs.reset(id)) :* resetGradient(id))
    }.toMap
    val inputGradient: Map[ID, Vector] = vertices.map { id =>
      id -> (
        network.updateInput.t * updateRawGradient(id) +
        network.resetInput.t * resetRawGradient(id))
    }.toMap
    val prevStateGradient: Map[ID, Vector] = vertices.map { id =>
      val edgeGradients = edges.getNeighbors(id).map(network.edgeMatrix.t * inputGradient(_))
      id -> (
        network.updateHidden.t * updateRawGradient(id) +
        network.resetHidden.t * resetRawGradient(id) +
        (1.0 - outputs.update(id)) :* stateGradient(id) +
        (network.activationHidden.t * tildeRawGradient(id)) :* outputs.reset(id) +
        vectorSum(network.size, edgeGradients))
    }.toMap
    // Network gradients.
    val activationInputGradient: Matrix = vertices.map { id =>
      tildeRawGradient(id) * outputs.input(id).t
    }.reduce(_ + _)
    val activationHiddenGradient: Matrix = vertices.map { id =>
      tildeRawGradient(id) * (outputs.reset(id) :* outputs.state(id)).t
    }.reduce(_ + _)
    val updateInputGradient: Matrix = vertices.map { id =>
      updateRawGradient(id) * outputs.input(id).t
    }.reduce(_ + _)
    val updateHiddenGradient: Matrix = vertices.map { id =>
      updateRawGradient(id) * outputs.state(id).t
    }.reduce(_ + _)
    val resetInputGradient: Matrix = vertices.map { id =>
      resetRawGradient(id) * outputs.input(id).t
    }.reduce(_ + _)
    val resetHiddenGradient: Matrix = vertices.map { id =>
      resetRawGradient(id) * outputs.state(id).t
    }.reduce(_ + _)
    val edgeBiasGradient: Vector = vertices.map { id =>
      inputGradient(id)
    }.reduce(_ + _)
    val edgeMatrixGradient: Matrix = vertices.map { id =>
      inputGradient(id) * outputs.visibleState(id).t
    }.reduce(_ + _)
  }

  val clipTo = 5.0
  def gradientMatrix(
    gradients: Seq[NetworkGradients], getter: NetworkGradients => Matrix): Matrix = {
    import breeze.numerics._
    val m = gradients.map(getter).reduce(_ + _)
    clip.inPlace(m, -clipTo, clipTo) // Mitigate exploding gradients.
    m
  }

  def gradientVector(
    gradients: Seq[NetworkGradients], getter: NetworkGradients => Vector): Vector = {
    import breeze.numerics._
    val m = gradients.map(getter).reduce(_ + _)
    clip.inPlace(m, -clipTo, clipTo) // Mitigate exploding gradients.
    m
  }

  def predict(
    dataIterator: Iterator[(ID, (Option[Double], Array[Double]))],
    edges: CompactUndirectedGraph,
    reversed: CompactUndirectedGraph): Iterator[(ID, Double)] = {
    assert(networkSize >= featureCount + 2, s"Network size must be at least ${featureCount + 2}.")
    val data = dataIterator.toSeq
    val labels = data.flatMap { case (id, (labelOpt, features)) => labelOpt.map(id -> _) }.toMap
    val features = data.map { case (id, (labelOpt, features)) => id -> features }.toMap
    val vertices = data.map(_._1)
    // Initial state contains label and features.
    val trueState: Map[ID, Vector] = vertices.map { id =>
      val state = DenseVector.zeros[Double](networkSize)
      if (labels.contains(id)) {
        state(0) = labels(id) // Label is in position 0.
        state(1) = 1.0 // Mark of a source of truth is in position 1.
      }
      val fs = features(id)
      for (i <- 0 until fs.size) {
        state(i + 2) = fs(i) // Features start from position 2.
      }
      id -> state
    }.toMap

    var network = Network.random(networkSize)(RandBasis.mt0) // Deterministic due to mt0.
    var adagradMemory = Network.zeros(networkSize)
    var finalOutputs: NetworkOutputs = null
    val random = new util.Random(0)
    for (i <- 1 to iterations) {
      // Forgets the label.
      def blanked(state: Vector) = {
        val s = state.copy
        s(0) = 0.0
        s(1) = 0.0
        s
      }
      // Initial states.
      val keptState = trueState.map {
        case (id, state) =>
          if (random.nextDouble < forgetFraction) id -> blanked(state) else id -> state
      }
      val initialState = if (!hideState) keptState else keptState.map {
        // In "hideState" mode neighbors can see the labels but it is hidden from the node itself.
        case (id, state) => id -> blanked(state)
      }

      // Forward pass.
      val outputs = (1 until radius).scanLeft {
        new NetworkOutputs(network, vertices, edges, initialState, keptState)
      } { (previous, r) =>
        new NetworkOutputs(network, vertices, edges, previous.newState, previous.newState)
      }

      // Backward pass.
      val errors: Map[ID, Double] = data.map {
        case (id, (Some(label), features)) =>
          // The label is predicted in position 0.
          id -> (outputs.last.newState(id)(0) - label)
        case (id, (None, features)) =>
          id -> 0.0
      }.toMap
      val errorTotal = errors.values.map(e => e * e).sum
      println(s"Total error in iteration $i: $errorTotal")
      val finalGradient: Map[ID, Vector] = errors.map {
        case (id, error) =>
          val vec = DenseVector.zeros[Double](networkSize)
          vec(0) = error
          id -> vec
      }
      val gradients = outputs.init.scanRight {
        new NetworkGradients(network, vertices, edges, finalGradient, outputs.last)
      } { (outputs, next) =>
        new NetworkGradients(network, vertices, edges, next.prevStateGradient, outputs)
      }
      val networkGradients = new Network(
        size = network.size,
        edgeMatrix = gradientMatrix(gradients, _.edgeMatrixGradient),
        edgeBias = gradientVector(gradients, _.edgeBiasGradient),
        resetInput = gradientMatrix(gradients, _.resetInputGradient),
        resetHidden = gradientMatrix(gradients, _.resetHiddenGradient),
        updateInput = gradientMatrix(gradients, _.updateInputGradient),
        updateHidden = gradientMatrix(gradients, _.updateHiddenGradient),
        activationInput = gradientMatrix(gradients, _.activationInputGradient),
        activationHidden = gradientMatrix(gradients, _.activationHiddenGradient))
      adagradMemory = adagradMemory.plus(networkGradients.squared)
      network = network.adagrad(adagradMemory, networkGradients)
      finalOutputs = outputs.last
    }
    // Return last predictions.
    finalOutputs.newState.map {
      case (id, state) => id -> state(0)
    }.iterator
  }
}
