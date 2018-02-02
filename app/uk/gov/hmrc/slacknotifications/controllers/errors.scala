/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.slacknotifications.controllers

import cats.data.NonEmptyList
import play.api.libs.json._

final case class ErrorMessage(errorMessage: String)

object ErrorMessage {
  implicit val writes: Writes[ErrorMessage] = Json.writes[ErrorMessage]
}

final case class Errors(errors: NonEmptyList[ErrorMessage])

object Errors {
  implicit def nonEmptyListWrites[A: Writes]: Writes[NonEmptyList[A]] =
    new Writes[NonEmptyList[A]] {
      def writes(nonEmptyList: NonEmptyList[A]): JsValue = Json.toJson(nonEmptyList.toList)
    }

  def one(errorMessage: String): Errors =
    Errors(NonEmptyList.of(ErrorMessage(errorMessage)))

  implicit val writes: Writes[Errors] = Json.writes[Errors]
}
