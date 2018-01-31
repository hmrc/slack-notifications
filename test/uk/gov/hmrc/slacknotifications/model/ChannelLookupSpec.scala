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

import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.{JsError, Json}
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.GithubRepository

class ChannelLookupSpec extends WordSpec with Matchers {
  "Channel lookup" should {
    "be possible by github repository name" in {
      val by       = "github-repository"
      val repoName = "a-repo-name"
      val json =
        s"""
          {
            "by" : "$by",
            "name" : "$repoName"
          }
        """

      Json.parse(json).as[ChannelLookup] shouldBe GithubRepository(by, repoName)
    }

    "fail if lookup method not specified" in {
      val json =
        s"""
          {
            "by" : "sth-not-valid-here",
            "name" : "a-repo-name"
          }
        """

      val parsingResult = Json.parse(json).validate[ChannelLookup]

      parsingResult shouldBe a[JsError]
      parsingResult.fold(
        invalid => invalid.head._2.head.message shouldBe "Unknown channel lookup type",
        _ => fail("didn't expect to be succeed")
      )
    }

    "be possible by github team name" in pending

    "be possible directly by slack channel name" in pending
  }
}
