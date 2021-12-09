/*
 * Copyright 2021 HM Revenue & Customs
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

import java.net.{URI, URL}

import scala.annotation.tailrec
import scala.util.Try

object AllowlistedLink {
  val LinkNotAllowlisted = "LINK NOT ALLOWLISTED"
  val allowedDomains = Set(
    "tax.service.gov.uk",
    "github.com",
    "pagerduty.com",
    "console.aws.amazon.com",
    "haveibeenpwned.com"
  )

  val getUris: String => Set[URL] = str => {
    val urlPattern = """(http[s]?.+?)(?="|`|\s|$)""".r
    urlPattern.findAllMatchIn(str)
      .map( m => Try {new URI(m.group(1)).toURL})
      .flatMap(_.toOption)
      .toSet
  }

  val isAllowlisted: (String, Set[String]) => Boolean = (url, allowlist) =>
    allowlist.exists(url.contains(_))

  @tailrec
  private def overrideLinks(str: String, badLinkMessage: String, links: List[URL]): String =
    links match {
      case h :: t => overrideLinks(str.replace(h.toString, badLinkMessage), badLinkMessage, t)
      case Nil    => str
    }

  val sanitise: String => String = str => {
    val badLinks: List[URL] = getUris(str).filter((x: URL) => !isAllowlisted(x.getHost, allowedDomains)).toList
    overrideLinks(str, LinkNotAllowlisted, badLinks)
  }
}
