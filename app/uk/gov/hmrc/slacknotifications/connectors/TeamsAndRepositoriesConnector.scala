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

package uk.gov.hmrc.slacknotifications.connectors

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(http: HttpClient,
                                              servicesConfig: ServicesConfig)
                                             (implicit ec: ExecutionContext) {

  private val url  = servicesConfig.baseUrl("teams-and-repositories")

  def getRepositoryDetails(repositoryName: String)(implicit hc: HeaderCarrier): Future[Option[RepositoryDetails]] =
    http.GET[Option[RepositoryDetails]](s"$url/api/repositories/$repositoryName")

}

final case class RepositoryDetails(teamNames: List[String], owningTeams: List[String])

object RepositoryDetails {
  implicit val format: Format[RepositoryDetails] = Json.format[RepositoryDetails]
}
