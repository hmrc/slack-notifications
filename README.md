# slack-notifications

[![Download](https://api.bintray.com/packages/hmrc/releases/slack-notifications/images/download.svg) ](https://bintray.com/hmrc/releases/slack-notifications/_latestVersion)

This service enables sending slack notifications on the MDTP.

Notifications can be sent to a correct slack channel based on specified criteria.

## Auth
This service uses Basic Auth for access control. If you want to use it please contact team PlatOps.

## Adding new users able to send Slack Notifications

The list of users that are able to use the service is predefined by an array in the config:

```
auth {
    authorizedServices = [
        {
            name = test
            password = "dGVzdA=="
            displayName = "My Bot"
            userEmoji = ":male-mechanic:"
        }
    ]
}
```
Where:
 * `name` is the username
 * `password` is a base64 encoded password for the user
 * Optional: `displayName` is a friendly name to use for sending messages as. If not set, will use `name` instead
 * Optional: `userEmoji` is the icon to use for when sending messages for this user
 
### Base64 encoded password

Please note that emitting `-n` will result in a new line character as a part of the base64 encoded string. Where this is unintentional, the password from the basic auth header will not match resulting in a 401 auth failed response.

```
echo -n "password" | base64
```
### In HMRC

If you would like to add a new user that is able to send Slack notifications then you will need to submit a PR to the following repos:

  1. https://github.com/hmrc/app-config-platapps-labs/blob/master/slack-notifications.yaml#L73-L74
  1. https://github.com/hmrc/app-config-platapps-live/blob/master/slack-notifications.yaml#L75-L76

> Remember to base64 and then encrypt the passwords (as described in the configs above)

Once we receive the PR we will review, before redeploying the app.

**N.B.** This only applies to users within the HMRC organisation on github

## Send to teams that own a repository

Sends slack messages to all teams contributing to a repo as shown in The Catalogue.
If a repository defines owners explicitly in the 'repository.yaml' file, Slack message will be sent only to those teams (relevant mostly for shared repos like app-config-*).

```
POST /slack-notifications/notification

body:

{
    "channelLookup" : {
        "by" : "github-repository",
        "repositoryName" : "name-of-a-repo"
    },
    "messageDetails" : {
        "text" : "message to be posted",
        "attachments" : [ // optional
            { "text" : "some-attachment" }
        ]    
    }
}
```

example curl request:
(assuming basic auth credentials for user: foo, pass: bar, i.e.: user:bar (Base64 encoded) = Zm9vOmJhcg==)

```
curl -X POST -H 'Content-type: application/json' -H 'Authorization: Basic Zm9vOmJhcg==' \
    --data '{"channelLookup" : { "by" : "github-repository", "repositoryName" : "foo" }, "messageDetails" : { "text" : "Testing if slack-notifications work" } }' \
    localhost:8866/slack-notifications/notification
```

## Send to multiple channels by specifying their names directly

```
POST /slack-notifications/notification

body:

{
    "channelLookup" : {
        "by" : "slack-channel",
        "slackChannels" : [
            "channel1",
            "channel2"
        ]
    },
    "messageDetails" : {
        "text" : "message to be posted",
        "attachments" : [ // optional
            { "text" : "some-attachment" }
        ]
    }
}
```

## Send to a team based on a Github username of one of its members

```
POST /slack-notifications/notification

body:

{
    "channelLookup" : {
        "by" : "teams-of-github-user",
        "githubUsername" : "a-github-username"
    },
    "messageDetails" : {
        "text" : "message to be posted",
        "attachments" : [ // optional
            "text" : "some-attachment"
        ]
    }
}
```

## Response

Response will typically have 200 status code and the following details:

```

{
    "successfullySentTo" : [
        "channel1",
        "channel2"
    ],
    "errors" : [
        {   
            "code" : "error_code_1",
            "message" : "Details of a problem"
        },
        {
            "code" : "error_code_2",
            "message" : "Details of another problem"
        }
    ],
    "exclusions" : [
        {
            "code" : "exclusion_code",
            "message" : "Details of why slack message was not sent"
        }
    ]
}

# error/exclusion codes are stable, messages may change

```

### Possible error codes are:

|Error Code                              | Meaning                                                                |
|----------------------------------------|------------------------------------------------------------------------|
|repository_not_found                    | A repository could not be found                                        |
|teams_not_found_for_repository          | The teams responsible for a repository could not be found              |
|teams_not_found_for_github_username     | No teams could be found for the given github username                  |
|slack_channel_not_found_for_team_in_ump | A slack channel was not found for a team in the User Management Portal |
|slack_channel_not_found                 | The slack channel was not found                                        |  
|slack_error                             | A generic error wrapping an exception coming directly from Slack       |

### Possible exclusion codes are:

|Exclusion Code                          | Meaning
|----------------------------------------|------------------------------------------------------------------------|
|not_a_real_team                         | Team is not a real MDTP team with human members                        |
|not_a_real_github_user                  | Github user is not a real person, e.g. CI user                         |

### Allowlisted domains

Any URL can be checked against the allowlisted domains stored in AllowlistedLink.scala.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
