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

package uk.gov.hmrc.slacknotifications.connectors

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.config.ServicesConfig
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  http: HttpClient,
  override val runModeConfiguration: Configuration,
  environment: Environment)(implicit ec: ExecutionContext)
    extends ServicesConfig {

  val mode = environment.mode
  val url  = baseUrl("teams-and-repositories")

  def getRepositoryDetails(repositoryName: String)(implicit hc: HeaderCarrier): Future[Option[RepositoryDetails]] =
    http.GET[Option[RepositoryDetails]](s"$url/api/repositories/$repositoryName")

}

final case class RepositoryDetails(teamNames: List[String], owningTeams: List[String])

object RepositoryDetails {
  implicit val format: Format[RepositoryDetails] = Json.format[RepositoryDetails]
}
