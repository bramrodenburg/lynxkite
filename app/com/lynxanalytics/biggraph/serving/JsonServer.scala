package com.lynxanalytics.biggraph.serving

import play.api.mvc
import play.api.libs.json
import com.lynxanalytics.biggraph.controllers
import com.lynxanalytics.biggraph.controllers._
import play.api.libs.functional.syntax.toContraFunctorOps
import play.api.libs.json.Json.toJsFieldJsValueWrapper

trait PrettyContoller[Q, S] {
  def process(request: Q): S
}

object JsonServer extends mvc.Controller {

/**
 * Implicit JSON inception
 *
 * json.Json.toJson needs one for every incepted case class,
 * they need to be ordered so that everything is declared before use.
 */

  implicit val rTestPost = json.Json.reads[controllers.TestPostRequest]
  implicit val wTestPost = json.Json.writes[controllers.TestPostResponse]

/**
 * Actions called by the web framework
 *
 * Play! uses the routings in /conf/routes to execute actions
 */

  def testPost = mvc.Action(parse.json) { request =>
    request.body.validate[controllers.TestPostRequest].fold({ errors =>
      BadRequest(json.Json.obj(
        "status" -> "Error",
        "message" -> "Bad JSON",
        "details" -> json.JsError.toFlatJson(errors)
      ))
    }, { request =>
      Ok(json.Json.toJson(controllers.TestController.process(request)))
    })
  }

}