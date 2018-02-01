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

package uk.gov.hmrc.slacknotifications.connectors

import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.{JsValue, Json}

class UserManagementConnectorSpec extends WordSpec with Matchers {

  "Extracting team slack channel" should {
    "work if slack field exists and contains team name at the end" in {
      val teamName      = "teamName"
      val slackLink     = "foo/" + teamName
      val json: JsValue = Json.obj("slack" -> slackLink)

      UserManagementConnector.extractSlackChannel(json) shouldBe Some(teamName)
    }

    "return None if slack field exists but there is no slack channel in it" in {
      val slackLink     = "link-without-team/"
      val json: JsValue = Json.obj("slack" -> slackLink)

      UserManagementConnector.extractSlackChannel(json) shouldBe None
    }

    "return None if slack field doesn't exist" in {
      UserManagementConnector.extractSlackChannel(Json.obj()) shouldBe None
    }
  }

}
