# slack-notifications

[![Build Status](https://travis-ci.org/hmrc/slack-notifications.svg)](https://travis-ci.org/hmrc/slack-notifications) [ ![Download](https://api.bintray.com/packages/hmrc/releases/slack-notifications/images/download.svg) ](https://bintray.com/hmrc/releases/slack-notifications/_latestVersion)

This service enables sending slack notifications on the MDTP.

Notifications can be sent to a correct slack channel based on specified criteria.

## Send to teams that own a repository

```
POST /slack-notifiations/notification 

body:

{
    "channelLookup" : {
        "by" : "github-repository",
        "githubRepository" : "name-of-a-repo"
    },
    "text" : "message to be posted",
    "username" : "deployments-info"
    "iconEmoji" : ":snowman:", // optional
    "attachments" : [ // optional
        "text" : "some-attachment"
    ]
}
```

## Send to multiple channels by specifying their names directly

```
POST /slack-notifiations/notification

body:

{
    "channelLookup" : {
        "by" : "slack-channel",
        "slackChannels" : [ 
            "channel1",
            "channel2" 
        ]
    },
    "text" : "message to be posted",
    "username" : "deployments-info" 
    "iconEmoji" : ":snowman:", // optional
    "attachments" : [ // optional
        "text" : "some-attachment"
    ]
}
```

## Response

Response will typically have 200 status code and following details:

```

{
    "successfullySentTo" : [ 
        "channel1",
        "channel2" 
    ],
    "errors" : [ 
        "Details of a problem",
        "Details of another problem"
    ],
    "exceptions" : [
        "Details of why slack message was not sent"
    ]
}

```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
