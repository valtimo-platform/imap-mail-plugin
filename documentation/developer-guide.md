# Developer guide

How the plugin works inside: the control flow, the concurrency and transaction boundaries,
and the decisions that are not obvious from the call graph.

This document assumes you have the source open. It does not repeat the configuration
reference — property names, types and defaults live in [plugin.md](plugin.md), and the
administrator's view of the same settings lives in the [handleiding](handleiding.md).

## Module layout

```
backend/plugin      the plugin itself; the only module that ships
backend/app         sandbox Valtimo application, see backend/app/README.md
frontend/projects/plugin   the Angular configuration UI
```

`backend/plugin` depends on Valtimo `compileOnly` throughout: a Valtimo application already
has `core`, `case`, `contract`, `plugin-valtimo`, `process-document` and
`temporary-resource-storage` on its classpath, and shipping a second copy would be a version
conflict waiting to happen. The exceptions are the ones a Valtimo application has no reason
to carry: `spring-boot-starter-mail`, which brings Jakarta Mail, is a real `implementation`
dependency.

## Shape: a poller, not a delegate

The plugin is not invoked from a process. Nothing calls it. It runs on a schedule and works
outward from the process links:

```
MailboxPollingService.pollMailboxes()          @Scheduled + @SchedulerLock
  └─ find every receive-mail process link      ValtimoPluginProcessLinkRepository
     └─ group by plugin configuration
        └─ ImapMailClient.poll(connection)     one IMAP/POP3 session per configuration
           └─ per message:
              IncomingMailHandler.handle()     @Transactional(REQUIRES_NEW)
                ├─ claim in imap_mail_processed
                ├─ MailMessageParser.parse()   MIME walk; body + attachments to storage
                ├─ filter the links            ReceiveMailProperties.matches
                └─ MailProcessStarter.start()  start a case, or resume one
           └─ postProcess(handled messages)    after the loop, still in the open folder
```

`receive-mail` is therefore a marker, not an action: it records on a BPMN element that this
mailbox feeds that element. The consequence worth internalising is that **the set of
mailboxes to poll is derived from the links, not from the configurations**. A plugin
configuration nobody links to is never opened, so creating one is harmless.

A link must name a fixed plugin configuration. Valtimo also allows a configuration resolved
from process variables at execution time, and that cannot work here — there is no execution
to resolve against until a mail has been read, which is the very thing the mailbox is needed
for. Such links are partitioned out in `pollAllConfigurations` and logged once per poll.

## Scheduling and concurrency

Three separate mechanisms, each covering a case the others do not.

**`@Scheduled`** on `pollMailboxes` and `pruneProcessedMail`, driven by
`valtimo.imap-mail.poll-cron` and `valtimo.imap-mail.retention-cron`. Scheduling is enabled
by the plugin's own `@EnableScheduling` on `ImapMailAutoConfiguration`, so an application
gets it by adding the dependency.

**An `AtomicBoolean`** in `MailboxPollingService` skips a poll while the previous one is
still running. Spring's default scheduler is single-threaded, so today this cannot trigger;
it exists because a mailbox on a slow server can outlast the interval, and the day someone
configures a scheduler pool the overlap would double-fetch every message.

**ShedLock** covers the case the flag cannot see: other nodes. Both scheduled methods carry
`@SchedulerLock` against the `shedlock` table, whose `LockProvider` Valtimo core already
supplies via `SchedulerAutoConfiguration`. This only takes effect if the *application*
carries `@EnableSchedulerLock`; without it ShedLock installs no interceptor and the
annotations are inert. Nothing breaks in that case — the claim table still makes a duplicate
case impossible — but every node opens the mailbox and races for every message.

`lockAtMostFor` on the poll is ten minutes. It is not a period, it is the deadline after
which a node that died mid-poll stops holding the lock, so it has to outlast a slow mailbox
rather than the interval.

## Claiming: how duplicates are prevented

Every handled mail gets a row in `imap_mail_processed`. The primary key is
`<pluginConfigurationId>|<identity>`, and the write is a `saveAndFlush` rather than a
deferred insert so that a concurrent claim from another node fails *before* the mail is
parsed and a case is created.

The key is scoped per configuration on purpose: one mail that arrives in two configured
mailboxes — both addresses were in `To` — should legitimately yield two cases.

`claimKey` falls back to a SHA-256 digest when the composed key exceeds the 512-character
column. Truncating instead would map two long, distinct `Message-ID`s onto one key and
silently drop the second mail as a duplicate.

### Deriving the identity

`ImapMailClient.identityOf` tries three sources in order, because each has a failure mode
the next one covers:

| Form | When | Why not sooner |
| --- | --- | --- |
| `msgid:<...>` | the message has a `Message-ID` | The best key there is: assigned by the sender, stable across servers and folders. |
| `uid:<host>/<folder>/<uidvalidity>/<uid>` | IMAP, no `Message-ID` | A UID is only unique within a folder, and only while `uidvalidity` holds — hence both in the key, so server-side renumbering cannot alias an old key onto a new message. |
| `digest:<sha256>` | neither of the above | Over host, folder, from, subject, sent date, received date and size. A last resort: two genuinely identical mails collide. |

### Transaction boundary

`IncomingMailHandler` is a separate bean from `MailboxPollingService`, not a method on it.
Transaction and authorization advice are applied by a proxy, so a self-call from the polling
loop would silently bypass both and every mail would be claimed and started outside a
transaction.

Claim, parse and process start share one `REQUIRES_NEW` transaction. A process that fails to
start therefore also releases the claim, and the mail is retried on the next poll. The other
trade-off — commit the claim first — would mean no retry, and a mail lost to a transient
failure stays lost.

The duplicate-key violation has to *escape* `handle` for the transaction to roll back
cleanly, so the caller catches `DataIntegrityViolationException` and counts it as skipped
rather than failed.

## Fetch and post-process

One `Store` connection per plugin configuration per poll, opened read-write when the
post-process action needs to change anything.

The candidate set depends on the action. With `MARK_READ` on a flag-capable protocol the
client searches `FlagTerm(SEEN, false)`, so only unseen messages are fetched; otherwise it
takes the folder contents. Messages already flagged `\Deleted` are filtered out in both
cases. `maxMessagesPerPoll` then caps the batch.

Post-processing runs **after** the handling loop, on the messages that were handled, while
the folder is still open:

- `MARK_READ` sets `\Seen`.
- `DELETE` sets `\Deleted`; the expunge happens on `folder.close(true)`.
- `MOVE` creates the target folder if needed, copies, then sets `\Deleted`. Copy before
  delete, deliberately: the reverse order risks losing mail if the copy fails.
- `NONE` does nothing.

A failure here is logged, not thrown. The processes have already started, so throwing would
roll nothing back — and the claim row stops the duplicate the un-applied flag would
otherwise cause.

`MailMessageParser.parse` must run while the folder is open, because a `MimeMessage` is only
readable then. That is why parsing happens inside the handler rather than after the session
closes, and why `FetchedMail` is a fully detached snapshot.

## Parsing

`MailMessageParser` walks the MIME tree exactly once. Every read of a part streams from the
server, so a second traversal would double the network cost of a large mail.

Nothing in the parser trusts the message. Inbound mail is attacker-controlled input, and
each of these is a bound rather than an assumption:

| Bound | Value | Against |
| --- | --- | --- |
| `MAX_MULTIPART_DEPTH` | 10 | A nested multipart deep enough to exhaust the stack. |
| `MAX_BODY_BYTES` | 10 MB | A body that claims one size and delivers another. |
| `MAX_TOTAL_ATTACHMENT_BYTES` | 25 MB | The same, across all attachments together. |
| `MAX_FILENAME_LENGTH` | 200 | A filename long enough to break storage. |

The two size caps are enforced *while reading* by `readBounded`, so an oversized mail is
refused rather than first pulled into memory. Exceeding either fails the mail, which leaves
it on the server.

**Body selection.** HTML wins over plain text when both are present, which is the
`multipart/alternative` case; `mailBodyIsHtml` reports which was chosen. A mail with neither
yields an empty body rather than a failure.

**Attachment detection.** A part counts as an attachment when its `Content-Disposition` says
so, *or* when it merely carries a filename. The second branch matters: some clients omit the
disposition and a `name=` in the `Content-Type` is then the only signal. Without it such a
part would be mistaken for body text.

**Filenames** are run through `safeFileName`, which strips path structure and characters
that mean something to a filesystem. Fixture 06 in the sandbox carries
`../../../etc/passwd` precisely to exercise this.

## From temporary storage to the case file

The body and every attachment go into Valtimo's temporary resource storage as they are read,
and only their resource ids travel as process variables. A 20 MB body has no business in the
Operaton database, and the SMTP mail plugin reads a body from that same storage when it
sends a reply, so answering an incoming mail needs no conversion step.

Temporary resource storage is a staging area, though. It is not the case file, nothing in
the UI lists it, and it is pruned. Getting a file onto a case's **Documents** tab means
turning it into one of the document's `relatedFiles`, which is a different thing entirely.

The bridge is a stock Valtimo mechanism, not something this plugin provides: publish
`TemporaryResourceSubmittedEvent(resourceId, documentId, documentDefinitionName)` and
`s3-resource`'s `TemporaryResourceSubmittedToS3EventListener` copies the resource into S3,
assigns it to the document, and deletes the temporary copy. The listener is behind
`valtimo.resource.s3.temp-upload-listener.enabled`.

Three things bite when wiring this up. The sandbox's `MailDocumentAttachments` is a worked
example of all three.

- **There must be a real `ResourceService`.** Without one, `relatedFiles` stays empty
  forever and the Documents tab has nothing to list. `local-resource` looks like the
  lightweight option but is a stub whose every method throws `NotImplementedError`; the
  choices are `s3-resource` or the Documenten API.
- **A bean called from a BPMN expression needs `@ProcessBean`.** Valtimo hands the process
  engine a whitelist of beans rather than the whole application context
  (`OperatonWhitelistedBeansPlugin`), so without it the expression fails at runtime with
  `Cannot resolve identifier '...'`.
- **`S3Resource` validates `extension` as not-blank.** An extensionless attachment therefore
  has to be renamed *before* the event is published — `patchResourceMetaData` on the
  temporary resource does it. Catching the exception afterwards does not help: the listener
  runs inside the delivery's transaction, so a rejection marks the transaction rollback-only
  and loses the entire mail rather than one attachment.

## Starting and resuming

`MailProcessStarter` decides between starting and resuming from the BPMN element the link is
attached to, not from configuration.

**`MESSAGE_START_EVENT_START`** starts something new. Which of two things depends on the
process: `ProcessPropertyService.isSystemProcessById` routes a system process to a bare
`correlateStartMessage`, and anything else to
`ProcessDocumentService.newDocumentAndStartProcess` — a case.

The document path has two guards worth knowing. It resolves the case definition through
`ProcessDefinitionCaseDefinitionService` and then checks that this is the **active** version;
without that check an old process link would keep creating cases against a superseded
version. It also requires `canInitializeDocument`.

**`RECEIVE_TASK_END`** and **`INTERMEDIATE_CATCH_EVENT_END`** resume. `runtimeService.signal`
for the first, `messageEventReceived` for the second, where the message name is read out of
the BPMN model by `messageNameOf`.

### Reply correlation

This is the part that is easy to get wrong, so it is worth stating plainly.

The execution query finds every instance parked at the activity, **across all cases**. That
set cannot be the answer: signalling all of them would file one citizen's reply — body and
attachments included — into every other case waiting at the same step.

`repliesTo` narrows it down using the mail thread:

1. A case remembers the `Message-ID` of the last mail it received, in the `mailMessageId`
   process variable.
2. A reply repeats that id in `In-Reply-To` / `References`, which every mail client
   maintains. `MailMessageParser.threadReferences` collects both into `mail.references`.
3. Only executions whose `mailMessageId` appears in that list are signalled.

A mail carrying no thread headers, or none that match an open case, resumes nothing and is
counted as skipped — `start` returns `false`, which the handler treats as "matched but had
nothing to act on", not as an error.

The corollary: a receive task or catch event cannot be used to start something from an
unrelated mail. That needs a message start event.

## Authorization

`IncomingMailHandler.handle` is annotated `@RunWithoutAuthorization` — the poller thread has
no authenticated user — but the annotation alone is not enough, and the reason is subtle.

Its aspect declares no order, so it runs *inside* the transaction advice and resets its
thread-local before the commit. Valtimo's task listeners fire on `AFTER_COMMIT` and read the
document to push an SSE update, which is a permission check. The poller therefore also wraps
the call in `runWithoutAuthorization { }` in `MailboxPollingService.pollConfiguration`,
outside the transaction, to keep the context open across the commit. The thread-local is
nesting-safe, so the inner annotation stays harmless.

Note that a mail-driven process which reaches a **user task** can still produce an
`AccessDeniedException: Unauthorized` from `TaskUpdateListener` after commit. Nothing is
rolled back — the case is created and resumed correctly — but the error appears in the log.
This is a property of any mail-driven process, not of a particular BPMN.

## Validation

`ImapMailConnectionProperties.validate` runs on **every poll**, not only when a configuration
is saved, because a configuration can also be created through the API. An invalid one fails
its poll loudly rather than being skipped silently.

Beyond the obvious not-blank and range checks, it enforces the combinations that the
protocol makes impossible: `MARK_READ` and `MOVE` need flags and folders respectively, which
POP3 has neither of; `MOVE` needs a `targetFolder` that differs from `folder`; POP3 accepts
no folder other than `INBOX`. `OAuth2Properties.validate` additionally requires an `https://`
token URL.

The Angular form mirrors these rules by showing and hiding fields — password only for
`BASIC`, the OAuth block only for `XOAUTH2`, `targetFolder` only for `MOVE`, STARTTLS only
for the non-implicit-TLS protocols — but the backend does not trust that and re-checks.

## Frontend

`imap-mail-plugin.specification.ts` is the whole contract with Valtimo's plugin management:
the plugin id, the logo, the configuration component, the component per action, and the `nl`
and `en` translation maps. The two components are ordinary reactive forms that emit their
value; conditional fields are driven by observables (`showOAuthFields$`, `showTargetFolder$`
and friends) rather than by template logic.

Adding a configuration property means touching four places: the Kotlin properties class, the
plugin definition, the form component, and both translation maps.

## Testing

`./gradlew :backend:plugin:test` — unit tests, with a Postgres container started through the
docker-compose Gradle plugin (`docker-resources/docker-compose-base-test.yml`).

The pieces designed to be testable without a mail server are the ones where the logic lives:
`MailProcessStarter` (which BPMN construct a link means), `identityOf` and `safeFileName`
(both `internal` for exactly this reason), and `ReceiveMailProperties.matches`.

For anything that does need a server, the sandbox in `backend/app` runs GreenMail with a set
of deliberately awkward fixtures — see [backend/app/README.md](../backend/app/README.md).

## Known limits

- Filters are positive, AND-ed `contains` only. There is no negation, so "subject contains
  X but not Re:" cannot be expressed; splitting intake from replies has to be done on an
  address.
- `recipientContains` matches the `To` addresses only. `Cc` is parsed into
  `mailCcRecipients` but never filtered on, so a mail that reaches the mailbox on `Cc` or
  through an alias absent from `To` is fetched and claimed but starts nothing.
- A reply is correlated by thread headers alone. A sender who composes a fresh mail instead
  of replying will not resume the case.
- `digest:` identities collide for two genuinely identical mails in a mailbox that provides
  neither `Message-ID` nor UIDs.
- Post-process failures leave the mailbox out of step with the claim table until the next
  poll. The claim table wins, so the effect is a mail that stays visible rather than a
  duplicate case.
