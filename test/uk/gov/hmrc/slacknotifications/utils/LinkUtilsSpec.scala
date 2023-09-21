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

package uk.gov.hmrc.slacknotifications.utils

import java.net.URL

import uk.gov.hmrc.slacknotifications.test.UnitSpec
import uk.gov.hmrc.slacknotifications.utils.LinkUtils._

class LinkUtilsSpec extends UnitSpec {
  "Links" can {
    "be extracted from message" in {
      val allowListedLink = "https://build.tax.service.gov.uk"
      getUris(allowListedLink) shouldBe Set(new URL(allowListedLink))
      getUris("http://url.i.dont?know=about") shouldBe Set(new URL("http://url.i.dont?know=about"))
    }
  }

  "Non catalogue Links" should {
    import org.scalatest.prop.TableDrivenPropertyChecks._

    "be marked as allow listed or not" in {
      val links = Table(
        heading = ("url"                                            , "is_allowlisted"),
        rows    = ("https://build.tax.service.gov.uk"               , true),
                  ("https://build.tax.service.gov.uk/login?from=%2F", true),
                  ("https://github.com/hmrc"                        , true),
                  ("https://kibana.tools.production.tax.service.gov.uk/app/kibana#/home?_g=()", true),
                  ("https://grafana.tools.production.tax.service.gov.uk/", true),
                  ("https://www.google.com"                         , false),
                  ("https://console.aws.amazon.com"                 , true),
                  ("http://url.i.dont?know=about"                   , false)
      )

      forAll(links) { (link: String, isAllowListedLink: Boolean) =>
        isAllowListed(new URL(link)) shouldBe isAllowListedLink
      }
    }

    "be replaced if they are not allow listed" in {
      val links = Table(
        heading = ("original_url"                                   , "sanitised_url"),
        rows    = ("https://build.tax.service.gov.uk"               , "https://build.tax.service.gov.uk"),
                  ("https://build.tax.service.gov.uk/login?from=%2F", "https://build.tax.service.gov.uk/login?from=%2F"),
                  ("https://github.com/hmrc"                        , "https://github.com/hmrc"),
                  ("https://kibana.tools.production.tax.service.gov.uk/app/kibana#/home?_g=()", "https://kibana.tools.production.tax.service.gov.uk/app/kibana#/home?_g=()"),
                  ("https://grafana.tools.production.tax.service.gov.uk/", "https://grafana.tools.production.tax.service.gov.uk/"),
                  ("https://www.google.com"                         , LinkUtils.LinkNotAllowListed),
                  ("http://url.i.dont?know=about"                   , LinkUtils.LinkNotAllowListed),
                  ("randomprefixhttps://example.com"                , "randomprefix" + LinkUtils.LinkNotAllowListed),
                  ("`http://url.i.dont?know=about`"                 , s"`${LinkUtils.LinkNotAllowListed}`"),
                  (""""http://url.i.dont?know=about""""             , s""""${LinkUtils.LinkNotAllowListed}""""),
                  ("https://hmrc.pagerduty.com/incidents/ABCDEF"    , "https://hmrc.pagerduty.com/incidents/ABCDEF")
      )

      forAll(links) { (link: String, sanitisedLink: String) =>
        updateLinks(link, "channel") shouldBe sanitisedLink
      }
    }
  }

  "Catalogue Links" should {
    import org.scalatest.prop.TableDrivenPropertyChecks._

    "not have an extra source added if already present" in {
      val links = Table(
        heading = ("original_url"                                                                , "expected_url"),
        rows    = ("https://catalogue.tax.service.gov.uk?source=slack-lds"                       , "https://catalogue.tax.service.gov.uk?source=slack-lds"),
                  ("https://catalogue.tax.service.gov.uk/repositories?from=here&source=slack-lds", "https://catalogue.tax.service.gov.uk/repositories?from=here&source=slack-lds")
      )

      forAll(links) { (link: String, sanitisedLink: String) =>
        updateLinks(link, "channel") shouldBe sanitisedLink
      }
    }

    "have source added if not present" in {
      val links = Table(
        heading = ("original_url"                                                        , "expected_url"),
        rows    = ("https://catalogue.tax.service.gov.uk"                                , "https://catalogue.tax.service.gov.uk?source=slack-channel"),
                  ("https://catalogue.tax.service.gov.uk/repositories?from=here"         , "https://catalogue.tax.service.gov.uk/repositories?from=here&source=slack-channel"),
                  ("https://catalogue.tax.service.gov.uk/repositories#fragment"          , "https://catalogue.tax.service.gov.uk/repositories?source=slack-channel#fragment"),
                  ("https://catalogue.tax.service.gov.uk/repositories?from=here#fragment", "https://catalogue.tax.service.gov.uk/repositories?from=here&source=slack-channel#fragment")
      )

      forAll(links) { (link: String, sanitisedLink: String) =>
        updateLinks(link, "channel") shouldBe sanitisedLink
      }
    }
  }
}
