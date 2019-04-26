/*
 * Copyright 2019 HM Revenue & Customs
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
import uk.gov.hmrc.slacknotifications.utils.WhitelistedLink

class SlackMessageSpec extends WordSpec with Matchers {
  "A slack message" should {
    val message = SlackMessage(
      channel = "slack_channel",
      text = "",
      username = "someone",
      None,
      Seq.empty
    )

    "contain a text message with whitelisted links" in {
      val message = SlackMessage(
        channel = "slack_channel",
        text = "Random text and link to https://github.com/hmrc",
        username = "someone",
        None,
        Seq.empty
      )
      val sanitisedMessage = SlackMessage.sanitise(message)
      sanitisedMessage shouldBe message
    }

    "contain a text with hidden non whitelisted links" in {
      val message = SlackMessage(
        channel = "slack_channel",
        text = "Evil text with links to http://very.bad.url/with-plenty-malware and http://url.i.dont?know=about",
        username = "someone",
        None,
        Seq.empty
      )
      val sanitisedMessage = SlackMessage.sanitise(message)

      val sanitisedText = s"Evil text with links to ${WhitelistedLink.overridenNonWhitelistedLink} and ${WhitelistedLink.overridenNonWhitelistedLink}"
      sanitisedMessage shouldBe message.copy(text = sanitisedText)
    }
  }
}
