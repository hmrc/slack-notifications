/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.slacknotifications.utils

import java.net.URL

import org.scalatest.{Matchers, WordSpec}
import uk.gov.hmrc.slacknotifications.utils.WhitelistedLink._

class WhitelistedLinkSpec extends WordSpec with Matchers {
  private val whitelistedLink = "https://build.tax.service.gov.uk"
  private val nonWhitelistedLink = "https://nonwhitelist.org"

  "Links" can {
    "be extracted from message" in {
      getUris(whitelistedLink) shouldBe Set(new URL(whitelistedLink))
      getUris("http://url.i.dont?know=about") shouldBe Set(new URL("http://url.i.dont?know=about"))
    }
  }

  "Links" should {
    import org.scalatest.prop.TableDrivenPropertyChecks._

    "be marked as whitelisted or not" in {
      val links = Table(
        ("url", "is_whitelisted"),
        ("https://build.tax.service.gov.uk", true),
        ("https://build.tax.service.gov.uk/login?from=%2F", true),
        ("https://github.com/hmrc", true),
        ("https://kibana.tools.production.tax.service.gov.uk/app/kibana#/home?_g=()", true),
        ("https://grafana.tools.production.tax.service.gov.uk/", true),
        ("https://www.google.com", false),
        ("https://console.aws.amazon.com", true),
        ("http://url.i.dont?know=about", false)
      )

      forAll(links) {
        (link: String, isWhiteListedLink: Boolean) =>
          isWhitelisted(link, whitelistedDomains) shouldBe isWhiteListedLink
      }
    }

    "be replaced if they aren't whitelisted" in {
      val links = Table(
        ("original_url", "sanitised_url"),
        ("https://build.tax.service.gov.uk", "https://build.tax.service.gov.uk"),
        ("https://build.tax.service.gov.uk/login?from=%2F", "https://build.tax.service.gov.uk/login?from=%2F"),
        ("https://github.com/hmrc", "https://github.com/hmrc"),
        ("https://kibana.tools.production.tax.service.gov.uk/app/kibana#/home?_g=()", "https://kibana.tools.production.tax.service.gov.uk/app/kibana#/home?_g=()"),
        ("https://grafana.tools.production.tax.service.gov.uk/", "https://grafana.tools.production.tax.service.gov.uk/"),
        ("https://www.google.com", WhitelistedLink.overridenNonWhitelistedLink),
        ("http://url.i.dont?know=about", WhitelistedLink.overridenNonWhitelistedLink),
        ("https://hmrc.pagerduty.com/incidents/ABCDEF", "https://hmrc.pagerduty.com/incidents/ABCDEF")
      )

      forAll(links) {
        (link: String, sanitisedLink: String) =>
          sanitise(link) shouldBe sanitisedLink
      }
    }
  }
}
