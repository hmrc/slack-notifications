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

import org.scalatest.prop.TableFor2
import play.api.libs.json.{JsArray, JsObject, JsString}
import play.api.Configuration
import uk.gov.hmrc.slacknotifications.config.DomainConfig
import uk.gov.hmrc.slacknotifications.base.UnitSpec

import java.net.URL

class LinkUtilsSpec extends UnitSpec:
  "Links" can:
    "be extracted from message" in:
      val allowListedLink = "https://build.tax.service.gov.uk"
      LinkUtils.getUris(allowListedLink) shouldBe Set(URL(allowListedLink))
      LinkUtils.getUris("http://url.i.dont?know=about") shouldBe Set(URL("http://url.i.dont?know=about"))

    "be extracted from a json string" in:
      val json: JsObject =
        JsObject(
          Map(
            "blocks" -> JsArray(
              Seq(
                JsObject(
                  Map(
                    "type" -> JsString("section"),
                    "text" -> JsObject(
                      Map(
                        "type" -> JsString("mrkdwn"),
                        "text" -> JsString("Go to https://domain1 and then Click <https://domain2/hmrc|here>")
                      )
                    )
                  )
                )
              )
            )
          )
        )

      LinkUtils.getUris(json.toString) shouldBe Set(URL("https://domain1"), URL("https://domain2/hmrc"))

  "Non catalogue Links" should:
    import org.scalatest.prop.TableDrivenPropertyChecks._

    "be marked as allow listed or not" in new Fixtures:
      val links: TableFor2[String, Boolean] =
        Table(
          heading = ("url"                           , "is_allowlisted"),
          rows    = ("https://domain1"               , true),
                    ("https://domain1/login?from=%2F", true),
                    ("https://www.google.com"        , false),
  
        )

      forAll(links): (link: String, isAllowListedLink: Boolean) =>
        LinkUtils.isAllowListed(new URL(link), domainConfig) shouldBe isAllowListedLink

    "be replaced if they are not allow listed" in new Fixtures:
      val links: TableFor2[String, String] =
        Table(
          heading = ("original_url"                  , "sanitised_url"),
          rows    = ("https://domain1"               , "https://domain1"),
                    ("https://domain1/login?from=%2F", "https://domain1/login?from=%2F"),
                    ("https://domain2/hmrc"          , "https://domain2/hmrc"),
                    ("https://www.google.com"        , domainConfig.linkNotAllowListed)
        )

      forAll(links): (link: String, sanitisedLink: String) =>
        LinkUtils.updateLinks(link, "channel", domainConfig) shouldBe sanitisedLink

  "Catalogue Links" should:
    import org.scalatest.prop.TableDrivenPropertyChecks._

    "not have an extra source added if already present" in new Fixtures:
      val links: TableFor2[String, String] =
        Table(
          heading = ("original_url"                                           , "expected_url"),
          rows    = ("https://domain1?source=slack-lds"                       , "https://domain1?source=slack-lds"),
                    ("https://domain1/repositories?from=here&source=slack-lds", "https://domain1/repositories?from=here&source=slack-lds")
        )

      forAll(links): (link: String, sanitisedLink: String) =>
        LinkUtils.updateLinks(link, "channel", domainConfig) shouldBe sanitisedLink

    "have source added if not present" in new Fixtures:
      val links: TableFor2[String, String] =
        Table(
          heading = ("original_url"                                                        , "expected_url"),
          rows    = ("https://catalogue.tax.service.gov.uk"                                , "https://catalogue.tax.service.gov.uk?source=slack-channel"),
                    ("https://catalogue.tax.service.gov.uk/repositories?from=here"         , "https://catalogue.tax.service.gov.uk/repositories?from=here&source=slack-channel"),
                    ("https://catalogue.tax.service.gov.uk/repositories#fragment"          , "https://catalogue.tax.service.gov.uk/repositories?source=slack-channel#fragment"),
                    ("https://catalogue.tax.service.gov.uk/repositories?from=here#fragment", "https://catalogue.tax.service.gov.uk/repositories?from=here&source=slack-channel#fragment")
        )

      forAll(links): (link: String, sanitisedLink: String) =>
        LinkUtils.updateLinks(link, "channel", domainConfig) shouldBe sanitisedLink

  trait Fixtures:
    val domainConfig: DomainConfig =
      DomainConfig(Configuration(
        "allowed.domains"    ->  Seq("domain1", "domain2", "catalogue.tax.service.gov.uk")
      , "linkNotAllowListed" -> "LINK NOT ALLOW LISTED"
      ))
