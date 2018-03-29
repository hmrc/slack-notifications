# slack-notifications

[![Build Status](https://travis-ci.org/hmrc/slack-notifications.svg)](https://travis-ci.org/hmrc/slack-notifications) [ ![Download](https://api.bintray.com/packages/hmrc/releases/slack-notifications/images/download.svg) ](https://bintray.com/hmrc/releases/slack-notifications/_latestVersion)

This service enables sending slack notifications on the MDTP.

Notifications can be sent to a correct slack channel based on specified criteria.

## Send to teams that own a repository

Sends slack messages to all teams contributing to a repo as shown in The Catalogue. 
If a repository defines owners explicitly in the 'repository.yaml' file, Slack message will be sent only to those teams (relevant mostly for shared repos like app-config-*).

```
POST /slack-notifications/notification 

body:

{
    "channelLookup" : {
        "by" : "github-repository",
        "githubRepository" : "name-of-a-repo"
    },
    "messageDetails" : {
        "text" : "message to be posted",
        "username" : "deployments-info"
        "iconEmoji" : ":snowman:", // optional
        "attachments" : [ // optional
            "text" : "some-attachment"
        ]    
    }
}
```

example curl request:
```
curl -X POST -H 'Content-type: application/json' \
    --data '{"channelLookup" : { "by" : "github-repository", "repositoryName" : "foo" }, "messageDetails" : { "text" : "Testing if slack-notifications work", "username" : "foo" } }' \
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
        "username" : "deployments-info" 
        "iconEmoji" : ":snowman:", // optional
        "attachments" : [ // optional
            "text" : "some-attachment"
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
        "username" : "deployments-info" 
        "iconEmoji" : ":snowman:", // optional
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

Possible error codes are: 

|Error Code                              | Status Code | Meaning                                                                       |
|----------------------------------------|-------------|-------------------------------------------------------------------------------|
|slack_error                             | 4xx/5xx     | A generic error occurring when the service is unable to notify a slack channel|
|repository_not_found                    | 200         | A repository could not be found                                               |
|teams_not_found_for_repository          | 200         | The teams responsible for a repository could not be found                     |
|teams_not_found_for_github_username     | 200         | No teams could be found for the given github user name                        |
|slack_channel_not_found_for_team_in_ump | 200         | A slack channel was not found for a team in the User Management Portal        |
|slack_channel_not_found                 | 200         | The slack channel was not found                                               |  


### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
