# IMAP mail Plugin

## Overview

Reads an external mailbox over IMAP or POP3 and starts a process — normally a case — for
each incoming e-mail.

The plugin polls; it is not called from a process. On a schedule it looks up every
`receive-mail` process link, opens the mailbox behind it, and for each message starts (or
resumes) the linked process with the mail as process variables. A mailbox with no
`receive-mail` link is never opened.

The body and the attachments are written to Valtimo's temporary resource storage and passed
on as resource ids, not as variable values. A 20 MB mail body has no business in the
Operaton database, and the SMTP mail plugin reads a body from that same storage when it
sends a reply — so answering an incoming mail needs no conversion step.

## Dependencies

### Backend

```kotlin
dependencies {
    implementation("com.ritense.valtimoplugins:imap-mail:0.0.1")
}
```

### Frontend

```json
{
  "dependencies": {
    "@valtimo-plugins/imap-mail-plugin": "0.0.1"
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

## Configuration

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
| `startTlsEnable`     | boolean | No                   | `true`                                      | Requires STARTTLS on `imap`/`pop3`. Ignored for the implicit-TLS protocols.       |
| `maxMessagesPerPoll` | number  | No                   | `25`                                        | Messages handled per poll; the rest follow next time.                             |
| `postProcessAction`  | string  | No                   | `MARK_READ`                                 | `MARK_READ`, `MOVE`, `DELETE` or `NONE`. See below.                               |
| `targetFolder`       | string  | With `MOVE`          |                                             | Must differ from `folder`.                                                        |
| `debug`              | boolean | No                   | `false`                                     | Logs the full protocol dialogue. See the warning below.                            |

Every combination is validated on each poll, not only when the configuration is saved,
because a configuration can also be created through the API. An invalid one fails its poll
loudly rather than being skipped silently.

### Microsoft 365

Microsoft has disabled Basic authentication for IMAP and POP3 in Exchange Online, so a
Microsoft 365 mailbox needs `authentication: XOAUTH2`:

- `oauthTokenUrl`: `https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token`
- `oauthScope`: leave empty to use `https://outlook.office365.com/.default`
- The app registration needs the `IMAP.AccessAsApp` application permission, with admin
  consent granted, and the service principal must be allowed access to the mailbox.

### After processing

`postProcessAction` decides what happens to a message once its process has started, and it
is the plugin's main defence against handling the same mail twice: a message that is marked
seen, moved away or deleted is not in the next poll's candidate set at all.

| Action      | Effect                                          | IMAP | POP3 |
|-------------|-------------------------------------------------|------|------|
| `MARK_READ` | Sets `\Seen`; only unseen messages are fetched. | Yes  | No   |
| `MOVE`      | Copies to `targetFolder`, then deletes.         | Yes  | No   |
| `DELETE`    | Sets `\Deleted` and expunges.                   | Yes  | Yes  |
| `NONE`      | Leaves the mailbox untouched.                   | Yes  | Yes  |

POP3 has no seen flag and no folders, so `MARK_READ` and `MOVE` are rejected for it up
front.

`NONE` is a debugging aid, not a production setting: every poll keeps seeing the message, so
the only thing preventing a duplicate case is the claim row in `imap_mail_processed` — and
those rows are pruned after the retention window.

### Duplicate handling

Every handled mail gets a row in `imap_mail_processed`, keyed by plugin configuration plus a
message identity (the `Message-ID` where present, otherwise the IMAP UID, otherwise a digest
of the pinning headers). The primary key is what makes running more than one node safe: both
fetch the message, both try to claim it, and the loser gets a constraint violation instead of
creating a second case.

The claim and the process start share one transaction, so a process that fails to start also
releases the claim and the mail is retried on the next poll.

The key is scoped per plugin configuration, so one mail that arrives in two configured
mailboxes correctly yields two cases.

### Application properties

| Property                            | Default        | Description                                                          |
|-------------------------------------|----------------|----------------------------------------------------------------------|
| `valtimo.imap-mail.polling-enabled` | `true`         | Set to `false` to install the plugin without letting it open mailboxes. |
| `valtimo.imap-mail.poll-cron`       | `0 */5 * * * *` | When to poll.                                                        |
| `valtimo.imap-mail.retention-cron`  | `0 30 3 * * *` | When to prune `imap_mail_processed`.                                 |
| `valtimo.imap-mail.retention-days`  | `90`           | How long claim rows are kept.                                        |

Overlapping polls are skipped rather than queued, so a mailbox on a slow server cannot cause
two polls to fetch the same messages concurrently.

### A note on `debug`

Enabling `debug` writes the entire protocol dialogue — senders, headers and full message
bodies — to the log, through the logger rather than stdout so log level and retention still
apply. The AUTH exchange is excluded. Treat it as a temporary diagnostic: incoming mail
routinely contains personal data.

## Actions

### Receive mail (`receive-mail`)

A marker rather than an action: it records on a BPMN element that this mailbox should start
or resume that process. Nothing invokes it — the poller finds the links.

Supported activity types:

| Activity type                    | Effect                                                                       |
|----------------------------------|------------------------------------------------------------------------------|
| Message start event              | Starts a new case per mail, or a bare instance for a system process.         |
| Receive task                     | Resumes the case this mail is a reply to.                                   |
| Intermediate catch event         | Resumes the case this mail is a reply to — e.g. an answer to a mail the case sent. |

The link must name a fixed plugin configuration. A configuration resolved from process
variables cannot work here: there is no execution to resolve against until a mail has been
read, which is the very thing the mailbox is needed for. Such links are logged and skipped.

#### How a reply finds its case

The filter says which mails a link is interested in; it does not say which of the waiting
cases a given mail belongs to. That second question is answered by the mail thread:

- A case remembers the `Message-ID` of the last mail it received, in `mailMessageId`.
- A reply repeats that id in its `In-Reply-To` and `References` headers, which every mail
  client maintains.
- Only the executions whose `mailMessageId` appears in the incoming mail's references are
  resumed.

A mail that carries no thread headers, or none that match an open case, resumes nothing and
is counted as skipped. This is deliberate: the alternative — signalling everything parked at
the activity — would file one sender's reply, body and attachments included, into every
other case waiting at the same step.

It also means a receive task or catch event cannot be used to start something from an
unrelated mail. Use a message start event for that.

| Parameter           | Type   | Required | Description                                                        |
|---------------------|--------|----------|--------------------------------------------------------------------|
| `senderContains`    | string | No       | Substring of the sender address.                                   |
| `subjectContains`   | string | No       | Substring of the subject.                                          |
| `recipientContains` | string | No       | Substring of any recipient.                                        |

All filters are case-insensitive and AND-ed. A link with no filters matches every message,
which is the common single-process setup. Several links on one mailbox, each with a
different filter, is how one mailbox feeds several processes.

### Process variables

| Variable                    | Type         | Description                                                     |
|-----------------------------|--------------|-----------------------------------------------------------------|
| `mailIdentity`              | string       | The key used for duplicate detection.                           |
| `mailMessageId`             | string       | `Message-ID` header, when present.                              |
| `mailSender`                | string       | Sender address.                                                 |
| `mailSenderName`            | string       | Sender display name, when present.                              |
| `mailRecipients`            | list<string> | `To` addresses.                                                 |
| `mailCcRecipients`          | list<string> | `Cc` addresses.                                                 |
| `mailSubject`               | string       | Subject.                                                        |
| `mailReferences`            | list<string> | `Message-ID`s of earlier mails in the thread, from `References` and `In-Reply-To`. |
| `mailReceivedAt`            | string       | ISO-8601 instant, when the server reports one.                   |
| `mailSentAt`                | string       | ISO-8601 instant, when the mail carries one.                     |
| `mailBodyResourceId`        | string       | Resource id of the body in temporary resource storage.           |
| `mailBodyIsHtml`            | boolean      | Whether that body is HTML. HTML wins over plain text when the two are `multipart/alternative` siblings. |
| `mailAttachmentResourceIds` | list<string> | Resource ids of the attachments.                                |
| `mailAttachmentCount`       | number       | Number of attachments.                                          |

Attachments are capped at 25 MB in total per mail and the body at 10 MB; a mail that exceeds
either fails and stays on the server. Both caps are enforced while reading, so an oversized
mail is refused rather than first pulled into memory. Attachment filenames are stripped of
path structure and of characters that mean something to a filesystem, since they come from
the sender.

## Usage

A mail-processing flow looks like this:

1. Configure the mailbox in the admin UI.
2. Give the case process a **message start event** and link it to `receive-mail`, with a
   filter if the mailbox feeds more than one process.
3. In the process, read `mailBodyResourceId` and `mailAttachmentResourceIds` to file the mail
   in the case.
4. Reply with the SMTP mail plugin, passing a body resource id and `mailSender` as the
   recipient.

To answer inside the same case later on, put an **intermediate catch event** or a **receive
task** on the reply step and link that to `receive-mail` too. Reply the mail with the SMTP
plugin using `mailMessageId` as `In-Reply-To`, so the sender's answer carries the id back in
its `References` header and the plugin can route it to this case.
