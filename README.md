# slack-notifications

[![Build Status](https://travis-ci.org/hmrc/slack-notifications.svg)](https://travis-ci.org/hmrc/slack-notifications) [ ![Download](https://api.bintray.com/packages/hmrc/releases/slack-notifications/images/download.svg) ](https://bintray.com/hmrc/releases/slack-notifications/_latestVersion)

This service enables sending slack notifications on the MDTP.

Notifications can be sent to a correct slack channel based on specified criteria.

## Send to teams that own a repository (as shown in The Catalogue)

```
POST /slack-notifiations/notification 

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

Response will typically have 201 status code and following details:

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

# error and exclusion codes are stable

```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
