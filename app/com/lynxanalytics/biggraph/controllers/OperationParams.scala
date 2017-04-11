// OperationParameterMeta subclasses that can be used to represent different types of operation
// parameters.
package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.model
import com.lynxanalytics.biggraph.serving.FrontendJson
import play.api.libs.json

object OperationParams {
  case class Param(
      id: String,
      title: String,
      defaultValue: String = "") extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {}
  }

  case class Choice(
      id: String,
      title: String,
      options: List[FEOption],
      multipleChoice: Boolean = false,
      allowUnknownOption: Boolean = false) extends OperationParameterMeta {
    val kind = "choice"
    val defaultValue = options.headOption.map(_.id).getOrElse("")
    def validate(value: String): Unit = {
      if (!allowUnknownOption) {
        val possibleValues = options.map { x => x.id }.toSet
        val givenValues: Set[String] = if (!multipleChoice) Set(value) else {
          if (value.isEmpty) Set() else value.split(",", -1).toSet
        }
        val unknown = givenValues -- possibleValues
        assert(
          unknown.isEmpty,
          s"Unknown option for $id: ${unknown.mkString(", ")}" +
            s" (Possibilities: ${possibleValues.mkString(", ")})")
      }
    }
  }

  case class ImportedTableParam(
      id: String,
      title: String) extends OperationParameterMeta {
    val kind = "imported-table"
    val options = List()
    val multipleChoice = false
    val defaultValue = ""
    def validate(value: String): Unit = {}
  }

  case class ExportedTableParam(
      id: String,
      title: String) extends OperationParameterMeta {
    val kind = "exported-table"
    val options = List()
    val multipleChoice = false
    val defaultValue = ""
    def validate(value: String): Unit = {}
  }

  case class TagList(
      id: String,
      title: String,
      options: List[FEOption]) extends OperationParameterMeta {
    val kind = "tag-list"
    val multipleChoice = true
    val defaultValue = ""
    def validate(value: String): Unit = {}
  }

  case class FileParam(id: String, title: String) extends OperationParameterMeta {
    val kind = "file"
    val multipleChoice = false
    val defaultValue = ""
    val options = List()
    def validate(value: String): Unit = {}
  }

  case class Ratio(id: String, title: String, defaultValue: String = "")
      extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {
      assert((value matches """\d+(\.\d+)?""") && (value.toDouble <= 1.0),
        s"$title ($value) has to be a ratio, a double between 0.0 and 1.0")
    }
  }

  case class NonNegInt(id: String, title: String, default: Int)
      extends OperationParameterMeta {
    val kind = "default"
    val defaultValue = default.toString
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {
      assert(value matches """\d+""", s"$title ($value) has to be a non negative integer")
    }
  }

  case class NonNegDouble(id: String, title: String, defaultValue: String = "")
      extends OperationParameterMeta {
    val kind = "default"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {
      assert(value matches """\d+(\.\d+)?""", s"$title ($value) has to be a non negative double")
    }
  }

  case class Code(
      id: String,
      title: String,
      language: String,
      defaultValue: String = "") extends OperationParameterMeta {
    val kind = "code"
    val options = List()
    val multipleChoice = false
    override val payload = Some(json.Json.obj("language" -> language))
    def validate(value: String): Unit = {}
  }

  // A random number to be used as default value for random seed parameters.
  case class RandomSeed(id: String, title: String) extends OperationParameterMeta {
    val defaultValue = util.Random.nextInt.toString
    val kind = "default"
    val options = List()
    val multipleChoice = false
    def validate(value: String): Unit = {
      assert(value matches """[+-]?\d+""", s"$title ($value) has to be an integer")
    }
  }

  case class ModelParams(
      id: String,
      title: String,
      models: Map[String, model.ModelMeta],
      attrs: List[FEOption]) extends OperationParameterMeta {
    val defaultValue = ""
    val kind = "model"
    val multipleChoice = false
    val options = List()
    import FrontendJson.wFEModelMeta
    import FrontendJson.wFEOption
    implicit val wModelsPayload = json.Json.writes[ModelsPayload]
    override val payload = Some(json.Json.toJson(ModelsPayload(
      models = models.toList.map { case (k, v) => model.Model.toMetaFE(k, v) },
      attrs = attrs)))
    def validate(value: String): Unit = {}
  }

  case class SegmentationParam(
      id: String,
      title: String,
      options: List[FEOption]) extends OperationParameterMeta {
    val kind = "segmentation"
    val multipleChoice = false
    val defaultValue = ""
    def validate(value: String): Unit = {}
  }
}

// A special parameter payload to describe applicable models on the UI.
case class ModelsPayload(
  models: List[model.FEModelMeta],
  attrs: List[FEOption])
