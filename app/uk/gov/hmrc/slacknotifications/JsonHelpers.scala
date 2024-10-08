/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.slacknotifications

import cats.data.NonEmptyList
import play.api.libs.json._

trait JsonHelpers:

  implicit def nonEmptyListWrites[A: Writes]: Writes[NonEmptyList[A]] =
    (nonEmptyList: NonEmptyList[A]) => Json.toJson(nonEmptyList.toList)

  implicit def nonEmptyListReads[A: Reads]: Reads[NonEmptyList[A]] =
    Reads { jsValue =>
      jsValue.validate[Seq[A]].flatMap:
        _.toList match
          case Nil => JsError("Expected a non-empty list")
          case head :: tail => JsSuccess(NonEmptyList(head, tail))
    }

object JsonHelpers extends JsonHelpers
