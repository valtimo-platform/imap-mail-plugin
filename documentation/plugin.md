# Plugin reference

Reads an external mailbox over IMAP or POP3 and starts a process — normally a case — for
each incoming e-mail.

This page is the reference: how to install the plugin, and the exact names, types and
defaults of everything it exposes. It deliberately explains as little as possible.

- For what the settings mean and how to fill them in, see the [handleiding](handleiding.md).
- For how any of it works, see the [developer guide](developer-guide.md).

## Installation

### Backend

```kotlin
dependencies {
    implementation("com.ritense.valtimoplugins:imap-mail:0.0.2")
}
```

### Frontend

```json
{
  "dependencies": {
    "@valtimo-plugins/imap-mail-plugin": "0.0.2"
  }
}
```

In your `app.module.ts`:

```typescript
import {
    ImapMailPluginModule, imapMailPluginSpecification,
} from '@valtimo-plugins/imap-mail-plugin';

@NgModule({
    imports: [
        ImapMailPluginModule,
    ],
    providers: [
        {
            provide: PLUGIN_TOKEN,
            useValue: [
                imapMailPluginSpecification,
            ]
        }
    ]
})
```

### Recommended, not required

Add `@EnableSchedulerLock` to the application class so that only one node polls a mailbox
per interval:

```kotlin
@SpringBootApplication
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
class Application
```

Valtimo core supplies the `LockProvider` and the `shedlock` table, so this annotation is the
only wiring needed. Without it the plugin still works and still cannot create a duplicate
case; every node just polls independently.

## Plugin configuration

| Property             | Type    | Required             | Default                                     | Description                                                                       |
|----------------------|---------|----------------------|---------------------------------------------|-----------------------------------------------------------------------------------|
| `host`               | string  | Yes                  |                                             | Mail server hostname.                                                             |
| `protocol`           | string  | No                   | `imaps`                                     | One of `imaps`, `imap`, `pop3s`, `pop3`.                                          |
| `port`               | number  | No                   | port of the protocol                        | 993 / 143 / 995 / 110 respectively.                                               |
| `username`           | string  | Yes                  |                                             | Usually the full mailbox address.                                                 |
| `password`           | secret  | With `BASIC`         |                                             | Not used with `XOAUTH2`.                                                          |
| `authentication`     | string  | No                   | `BASIC`                                     | `BASIC` or `XOAUTH2`.                                                             |
| `oauthTokenUrl`      | string  | With `XOAUTH2`       |                                             | Full token endpoint URL. Must be https.                                           |
| `oauthClientId`      | string  | With `XOAUTH2`       |                                             |                                                                                   |
| `oauthClientSecret`  | secret  | With `XOAUTH2`       |                                             |                                                                                   |
| `oauthScope`         | string  | No                   | `https://outlook.office365.com/.default`    | Client-credentials scope.                                                          |
| `folder`             | string  | No                   | `INBOX`                                     | IMAP only; POP3 accepts `INBOX` alone.                                            |
| `startTlsEnable`     | boolean | No                   | `true`                                      | Requires STARTTLS on `imap`/`pop3`. Ignored for the implicit-TLS protocols. Switching it off on those protocols logs a warning on every poll: credentials and message content then cross the network unencrypted. |
| `maxMessagesPerPoll` | number  | No                   | `25`                                        | Messages handled per poll; the rest follow next time.                             |
| `postProcessAction`  | string  | No                   | `MARK_READ`                                 | `MARK_READ`, `MOVE`, `DELETE` or `NONE`.                                          |
| `targetFolder`       | string  | With `MOVE`          |                                             | Must differ from `folder`.                                                        |
| `debug`              | boolean | No                   | `false`                                     | Logs the full protocol dialogue, message content included.                        |

`MARK_READ` and `MOVE` are rejected for POP3, which has neither a seen flag nor folders.

## Application properties

| Property                            | Default         | Description                                                            |
|-------------------------------------|-----------------|------------------------------------------------------------------------|
| `valtimo.imap-mail.polling-enabled` | `true`          | Set to `false` to install the plugin without letting it open mailboxes. |
| `valtimo.imap-mail.poll-cron`       | `0 */5 * * * *` | When to poll.                                                          |
| `valtimo.imap-mail.retention-cron`  | `0 30 3 * * *`  | When to prune `imap_mail_processed`.                                   |
| `valtimo.imap-mail.retention-days`  | `90`            | How long claim rows are kept.                                          |

## Action: receive mail (`receive-mail`)

A marker rather than an action: it records on a BPMN element that this mailbox should start
or resume that process. Nothing invokes it — the poller finds the links.

Supported on a message start event, a receive task and an intermediate catch event. The link
must name a fixed plugin configuration, not one resolved from process variables.

| Parameter           | Type   | Required | Description                        |
|---------------------|--------|----------|------------------------------------|
| `senderContains`    | string | No       | Substring of the sender address.   |
| `subjectContains`   | string | No       | Substring of the subject.          |
| `recipientContains` | string | No       | Substring of a `To` address.       |

All filters are case-insensitive and AND-ed. A link with no filters matches every message.

`recipientContains` matches against the `To` header only. A mail that reaches the mailbox on
`Cc`, `Bcc` or through an alias that never appears in `To` will not match, even though the
plugin did fetch it.

## Process variables

Set on the process instance the plugin starts or resumes.

| Variable                    | Type         | Description                                                     |
|-----------------------------|--------------|-----------------------------------------------------------------|
| `mailIdentity`              | string       | The key used for duplicate detection.                           |
| `mailMessageId`             | string       | `Message-ID` header, when present. Also what reply correlation reads. |
| `mailSender`                | string       | Sender address.                                                 |
| `mailSenderName`            | string       | Sender display name, when present.                              |
| `mailRecipients`            | list<string> | `To` addresses.                                                 |
| `mailCcRecipients`          | list<string> | `Cc` addresses.                                                 |
| `mailSubject`               | string       | Subject.                                                        |
| `mailReferences`            | list<string> | `Message-ID`s of earlier mails in the thread, from `References` and `In-Reply-To`. |
| `mailReceivedAt`            | string       | ISO-8601 instant, when the server reports one.                   |
| `mailSentAt`                | string       | ISO-8601 instant, when the mail carries one.                     |
| `mailBodyResourceId`        | string       | Resource id of the body in temporary resource storage.           |
| `mailBodyIsHtml`            | boolean      | Whether that body is HTML.                                       |
| `mailAttachmentResourceIds` | list<string> | Resource ids of the attachments.                                |
| `mailAttachmentCount`       | number       | Number of attachments.                                          |

## Limits

| Limit | Value |
|-------|-------|
| Body size | 10 MB |
| Attachments per mail, combined | 25 MB |
| Attachments per mail, count | 100 |
| Multipart nesting depth | 10 |
| Attachment filename length | 200 characters |

Exceeding a size or count limit refuses the mail: no case is created, the failure is logged
at error level, and the mail is post-processed (marked read, moved or deleted) like a handled
one. It is deliberately not left on the server — it would be refused identically on every
later poll while occupying one of the `maxMessagesPerPoll` slots.

## Database

| Table                  | Purpose                                                          |
|------------------------|------------------------------------------------------------------|
| `imap_mail_processed`  | One row per handled mail. The primary key is what prevents duplicates. |
| `shedlock`             | Provided by Valtimo core; used by the poll and the retention sweep. |
