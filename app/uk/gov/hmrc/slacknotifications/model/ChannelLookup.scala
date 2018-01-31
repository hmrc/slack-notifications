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

package uk.gov.hmrc.slacknotifications.model

import play.api.libs.json.Reads._
import play.api.libs.json.{Json, Reads, _}

trait ChannelLookup {
  def by: String
}

object ChannelLookup {

  final case class GithubRepository(by: String, name: String) extends ChannelLookup

  val githubRepositoryReads = Json.reads[GithubRepository].map(upcastAsChannelLookup)

  implicit val reads: Reads[ChannelLookup] = new Reads[ChannelLookup] {
    def reads(json: JsValue): JsResult[ChannelLookup] =
      (json \ "by").validate[String].flatMap {
        case "github-repository" => json.validate(githubRepositoryReads)
        case _                   => JsError("Unknown channel lookup type")
      }
  }

  private def upcastAsChannelLookup[A <: ChannelLookup](a: A): ChannelLookup = a: ChannelLookup

}
