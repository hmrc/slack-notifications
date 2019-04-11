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

package uk.gov.hmrc.slacknotifications.services

import cats.data.NonEmptyList
import com.google.common.io.BaseEncoding
import com.typesafe.config.ConfigFactory
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.{Matchers, WordSpec}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import pureconfig.error.ConfigReaderException
import uk.gov.hmrc.slacknotifications.model.ChannelLookup.SlackChannel
import uk.gov.hmrc.slacknotifications.model.{Attachment, MessageDetails, NotificationRequest}
import uk.gov.hmrc.slacknotifications.services.AuthService.{AuthConfiguration, Service}

class AuthServiceSpec extends WordSpec with Matchers with ScalaCheckPropertyChecks {

  "Checking if user is authorised" should {

    "return true if the service is present in the configuration" in {
      val service = Service("foo", "bar", "foo")

      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = true
            authorizedServices = [
              {
                name = ${service.name}
                password = ${base64Encode(service.password)}
                displayName = ${service.displayName}
              }
            ]
            authorizedUrls = []
          }
         """
      )

      val configuration = Configuration(typesafeConfig)

      val authService = new AuthService(configuration)

      authService.isAuthorized(Some(service)) shouldBe true
    }

    "return true if the service is present in the configuration (app-config-* style)" in {
      val service = Service("foo", "bar", "boo")

      val configuration =
        Configuration(
          "auth.enabled" -> true,
          "auth.authorizedServices.0.name" -> service.name,
          "auth.authorizedServices.0.password" -> base64Encode(service.password),
          "auth.authorizedServices.0.displayName" -> service.displayName,
          "auth.authorizedUrls.0" -> ""
        )

      val authService = new AuthService(configuration)

      authService.isAuthorized(Some(service)) shouldBe true
    }

    "return false if no matching service is found in config" in {
      val service = Service("foo", "bar", "foo")
      val configuration =
        Configuration(
          "auth.enabled" -> true,
          "auth.authorizedServices.0.name" -> service.name,
          "auth.authorizedServices.0.password" -> service.password,
          "auth.authorizedServices.0.displayName" -> service.displayName,
          "auth.authorizedUrls.0" -> ""
        )

      val authService = new AuthService(configuration)

      val anotherServiceNotInConfig = Service("x", "y", "z")

      authService.isAuthorized(Some(anotherServiceNotInConfig)) shouldBe false
    }

    "return true if auth not enabled" in {
      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = false
            authorizedServices = []
            authorizedUrls = []
          }
         """
      )

      val authService = new AuthService(Configuration(typesafeConfig))

      authService.isAuthorized(Some(Service("foo", "bar", "foo"))) shouldBe true
      authService.isAuthorized(None) shouldBe true
    }

  }

  "Check if a URL provided in the request" should {

    "return false if the URL is not whitelisted" in {
      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = false
            authorizedServices = []
            authorizedUrls = []
          }
         """
      )

      val urlGenerator: Gen[String] = arbitrary[String]

      forAll(urlGenerator -> "url") { url: String =>
        val authService = new AuthService(Configuration(typesafeConfig))
        authService.isAuthorizedUrl(s"http://$url.com") shouldBe false
      }
    }

    "return true if the URL is whitelisted" in {
      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = true
            authorizedServices = []
            authorizedUrls = [
            "https://jira.tools.tax.service.gov.uk"
            ]
          }
         """
      )
        val authService = new AuthService(Configuration(typesafeConfig))
        authService.isAuthorizedUrl("https://jira.tools.tax.service.gov.uk") shouldBe true
    }

  }

  "Filter fields for URLs" should {

    "correctly identify the presence of URLs" in {
      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = true
            authorizedServices = []
            authorizedUrls = []
          }
         """
      )

      val authService = new AuthService(Configuration(typesafeConfig))

      val result = authService.filterFieldsForURLs(Array(
        "https://jira.tools.tax.service.gov.uk",
        "there is a URL here http://jira.tools.tax.service.gov.uk in this text"))

      result.length should be(2)
    }

  }

  "Check if a request contains only valid URLs" should {

    "return true if there are only whitelisted URLs present" in {
      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = true
            authorizedServices = []
            authorizedUrls = [
            "https://jira.tools.tax.service.gov.uk",
            "https://aws.amazon.com"
            ]
          }
         """
      )
      val authService = new AuthService(Configuration(typesafeConfig))

      val notificationRequest = NotificationRequest(
        SlackChannel("", NonEmptyList.of("test")),
        MessageDetails("", "aaaaaaa", None, Seq(Attachment(None,None,Some("aaaaaaaa"),None,None,
          None,None,Some("https://aws.amazon.com"),Some("https://jira.tools.tax.service.gov.uk"),
          None,None,Some("aws.amazon.com"),None,Some("jira.tools.tax.service.gov.uk"),None))))

      authService.isValidatedNotificationRequest(notificationRequest) shouldBe true
    }

    "return false if there are any non-whitelisted URLs present" in {
      val typesafeConfig = ConfigFactory.parseString(
        s"""
          auth {
            enabled = true
            authorizedServices = []
            authorizedUrls = [
            "https://jira.tools.tax.service.gov.uk",
            "https://aws.amazon.com"
            ]
          }
         """
      )
      val authService = new AuthService(Configuration(typesafeConfig))

      val urlGenerator: Gen[String] = arbitrary[String]

      forAll(urlGenerator -> "url") { url: String =>
        val notificationRequest = NotificationRequest(
          SlackChannel("", NonEmptyList.of("test")),
          MessageDetails("", s"http://$url.com", None, Seq(Attachment(Some("aaaaaaaaa"),None,None,
            None,None,None,None,Some("https://jira.tools.tax.service.gov.uk"),Some(s"https://$url.co.uk"),
            None,None,None,None,Some(s"$url.com"),None))))
        authService.isValidatedNotificationRequest(notificationRequest) shouldBe false
      }
    }
  }

  "Instantiating AuthService" should {
    "fail if password is not base64 encoded" in {
      val configuration =
        Configuration(
          "auth.enabled" -> true,
          "auth.authorizedServices.0.name" -> "name",
          "auth.authorizedServices.0.password" -> "not base64 encoded $%£*&^",
          "auth.authorizedServices.0.displayName" -> "displayName",
          "auth.authorizedUrls.0" -> ""
        )

      val exception = intercept[ConfigReaderException[AuthConfiguration]] {
        new AuthService(configuration)
      }

      exception.getMessage() should include("password was not base64 encoded")
    }
  }

  def base64Encode(s: String): String =
    BaseEncoding.base64().encode(s.getBytes("UTF-8"))

}
