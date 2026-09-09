# Sandbox application

A throwaway environment for exercising the IMAP mail plugin end to end: a fake mail server
with a mailbox full of awkward e-mail, a Valtimo case that a mail creates, and a script to
drop new mail in while the application runs.

Nothing in this module ships. It exists so that "does this plugin actually work" can be
answered without a real mailbox, real credentials or a colleague's Exchange tenant.

Alongside the application source, this module holds `docker-compose.yml` (the stack),
`fixtures/` (the e-mail), `tools/` (the mail client used by the script) and `dev.sh` (the
entry point).

## Quick start

`dev.sh` paths below are relative to this directory; from the repository root it is
`./backend/app/dev.sh`. Gradle is always run from the root.

```
./dev.sh up                         # mail server, database, Keycloak
./gradlew :backend:app:bootRun      # starts the stack too, if it is not already up
```

The application polls the mailbox every 15 seconds (`valtimo.imap-mail.poll-cron` in
`src/main/resources/config/application.yml`, overriding the plugin's 5-minute
default). Within one interval the log shows a line like:

```
Polled imap://zaakpost@valtimo.local@localhost:3143/INBOX: 7 fetched, 6 started, 1 skipped, 0 failed
```

and six cases appear at <http://localhost:8080>. Then drop a new mail in:

```
./dev.sh send new-request               # creates a case
./dev.sh send reply-to-request          # resumes cases waiting at the receive task
./dev.sh send duplicate-of-01           # recognised as already handled, no second case
```

`./dev.sh help` lists everything. `./dev.sh fixtures` lists the fixtures.

## What is running

| Service | Address | Notes |
| --- | --- | --- |
| GreenMail | `localhost:3143` IMAP, `:3025` SMTP | also IMAPS 3993, POP3 3110, POP3S 3995, SMTPS 3465 |
| GreenMail REST API | <http://localhost:8082/api/user> | users and server configuration |
| Valtimo database | `localhost:54360` | `plugin` / `password`, database `plugin` |
| Keycloak | <http://localhost:8081/auth> | `admin` / `admin`, realm `valtimo` |

The Valtimo application itself is **not** containerised - it runs on the host under
`bootRun`, which is why the plugin configuration connects to `localhost` rather than to a
container name.

[GreenMail](https://greenmail-mail-test.github.io/greenmail/) is a test double, not a mail
server. It keeps everything in memory, so restarting its container is how you get a clean
mailbox, and it runs with `-Dgreenmail.auth.disabled` so any password is accepted - a fixture
should fail because of what the plugin does with it, never because of credentials.

### Port conflicts

The Valtimo database sits on **54360**, not the more usual 54320, because the GZAC compose
stack publishes its own database there and the two need to run side by side. Keycloak is
still on 8081 because both `application.yml` and
`frontend/src/environments/auth/keycloak-config.dev.ts` hardcode it.

If something else already serves a Valtimo realm on 8081 - the GZAC stack does - skip ours
and reuse it:

```
./dev.sh up --no-keycloak
COMPOSE_PROFILES= ./gradlew :backend:app:bootRun    # so bootRun does not start it either
```

Everything else is movable: `cp ports.example .env` and edit. `dev.sh up` checks the
ports first and says which one is taken rather than letting compose fail with a bare "Bind
for 0.0.0.0:54320 failed".

## The case

`src/main/resources/config/case/mail-intake/1-0-0/` holds a single case
definition, `mail-intake`, whose process carries two `receive-mail` process links. Those
links are the reason the poller opens the mailbox at all: `MailboxPollingService` derives the
mailboxes to poll from the links, not from the plugin configurations, so a configuration
nobody links to is never touched.

```
(mail) --> [ await-reply ] --> ( Review the application ) --> (end)
 start        receive task            user task
 event
```

| Element | Type | Link filter | Effect |
| --- | --- | --- | --- |
| `mail-received` | message start event | subject contains `Aanvraag`, To contains `zaakpost@valtimo.local` | creates a case |
| `await-reply` | receive task | To contains `zaakpost+antwoord@valtimo.local` | resumes waiting cases |

The receive task deliberately comes *before* the user task, so a case reaches the waiting
state the moment it is created and the reply path is testable without clicking a task first.

Execution listeners copy the `mail*` process variables into the document, because process
variables are not visible in the case UI and are pruned along with history, while the
document is the case file. The body and attachments stay out of both: they live in temporary
resource storage and only their resource ids travel as variables.

### Why the reply uses a different address

`ReceiveMailProperties` can only express positive, AND-ed `contains` filters. There is no way
to say "subject contains Aanvraag but *not* Re:", so filtering intake and replies apart on
the subject is impossible - `Re: Aanvraag parkeervergunning` contains `Aanvraag`, and both
links would fire for the same mail.

So the split is on an address the two filters disagree about. Replies are addressed to the
plus-address `zaakpost+antwoord@valtimo.local`, which does not contain the literal
`zaakpost@valtimo.local` that the intake filter looks for. The `X-Fixture-Envelope-To` header
in the reply fixture makes `dev.sh send` deliver it to the real mailbox anyway, which is what
an alias or catch-all does on a real server.

## Fixtures

`fixtures/inbox/` is mounted straight onto GreenMail's preload path, so every file in it
is in the mailbox when the container starts. Fixtures are delivered as raw bytes - whatever
the `.eml` says on disk is exactly what the plugin has to deal with.

| Fixture | Exercises |
| --- | --- |
| `01-plain-text.eml` | the ordinary case: `text/plain`, `Message-ID` present, so the identity is `msgid:` |
| `02-html-alternative.eml` | `multipart/alternative`; the HTML part should win and `mailBodyIsHtml` should be true |
| `03-attachments.eml` | a PDF with `Content-Disposition: attachment`, plus a CSV that only has a `name=` in its `Content-Type` - the branch that stops such a part being mistaken for body text |
| `04-no-message-id.eml` | no `Message-ID`, so the identity falls back to `uid:<host>/<folder>/<uidvalidity>/<uid>`; also a bare `From` address, so `mailSenderName` is absent |
| `05-encoded-headers.eml` | RFC 2047 encoded-words in `Subject` and `From`, a quoted-printable body with `é ø š § —`, and two Cc addresses |
| `06-nested-multipart.eml` | `mixed > related > alternative` three deep, an inline image referenced by `Content-ID`, and an attachment whose filename is `../../../etc/passwd` |
| `07-other-recipient.eml` | addressed `To: info@valtimo.local` with the sandbox mailbox only on Cc, so the intake filter does not match - fetched, claimed, marked read, no case |

`fixtures/send/` is for mail you inject while the application runs:

| Fixture | Exercises |
| --- | --- |
| `new-request` | a plain new request, so you can watch poll → claim → parse → case happen live |
| `reply-to-request` | resumes cases waiting at the receive task |
| `duplicate-of-01` | same `Message-ID` as fixture 01, so the claim in `imap_mail_processed` makes it a duplicate |

Adding one is just dropping an `.eml` in either directory. Inbox fixtures need
`./dev.sh reset` to be picked up.

## Things worth watching

**One reply resumes every waiting case.** `MailProcessStarter.signalWaitingExecutions` queries
executions by process definition and activity id and signals all of them, so sending
`reply-to-request` once resumes *all* the cases sitting at `await-reply`, not the one whose
mail it is actually a reply to. The fixture carries `In-Reply-To` and `References` pointing at
fixture 01, and neither is used for correlation. If a mailbox is meant to feed replies back
into the specific case that sent the original, that correlation does not exist yet.

**`AccessDeniedException: Unauthorized` when a user task appears.** Sending
`reply-to-request` logs one of these per resumed case:

```
ERROR ... TransactionSynchronizationUtils : TransactionSynchronization.afterCompletion threw exception
org.springframework.security.access.AccessDeniedException: Unauthorized
    at com.ritense.authorization.ValtimoAuthorizationService.requirePermission
    at ...OperatonProcessJsonSchemaDocumentService.getDocument
    at com.ritense.processdocument.sse.domain.listener.TaskUpdateListener.handle
```

Nothing is rolled back - the cases are created and resumed correctly - but it is worth
understanding. `IncomingMailHandler.handle` is annotated `@RunWithoutAuthorization`, and that
covers the transaction. `TaskUpdateListener` is an SSE listener that runs *after commit*, by
which point the authorization context is gone, and the poller thread has no authenticated
user to fall back on. It only fires when a token reaches a **user task**, which is why the
first poll is silent (cases stop at the receive task) and only the resume produces it.

Any mail-driven process that reaches a user task will hit this, so it is not an artefact of
this particular BPMN.

**`MARK_READ` and the seen flag.** The sandbox configuration uses `MARK_READ`, so only unseen
messages are candidates and the mail stays in the folder where you can look at it.
`./dev.sh show` uses `BODY.PEEK`, so inspecting the mailbox does not set `\Seen` and
change what the next poll considers. To try the other actions, edit
`src/main/resources/config/plugin/imap-mail.pluginconfig.json`:

```json
"postProcessAction": "MOVE",
"targetFolder": "Verwerkt"
```

GreenMail creates the folder on demand, and `./dev.sh show Verwerkt` will show what moved.
`NONE` leaves the mailbox untouched and is worth trying once to watch the
`imap_mail_processed` table become the only thing preventing duplicates.

**Re-running the fixtures.** Two pieces of state decide whether a fixture is processed again:
the `\Seen` flag on the server and the claim row in the database.

```
./dev.sh reset            # fresh mailbox from the fixtures; database untouched
./dev.sh unclaim          # drop the claims; mailbox untouched
./dev.sh reset --all      # both, plus cases and Keycloak's database
```

`reset` alone is usually *not* what you want after a successful run - the fixtures come back
unseen but their claims still exist, so they are skipped as duplicates. That combination is
itself worth seeing once, and then `unclaim` clears it.

**Protocol traces.** Set `"debug": true` in the plugin configuration to get the client side of
the IMAP dialogue through the application's logger (the plugin routes it through the logger
rather than stdout on purpose). Add `-Dgreenmail.verbose` to `GREENMAIL_OPTS` in
`docker-compose.yml` for the server side. Both print message content, which is why they are
off by default.

**Other protocols.** GreenMail serves POP3 on 3110, so `"protocol": "pop3"`, `"port": 3110`
and `"postProcessAction": "DELETE"` exercises the POP3 path and the validation that rejects
`MARK_READ` and `MOVE` there. Its IMAPS certificate is self-signed, so pointing the plugin at
3993 fails certificate validation - deliberately, since the client sets
`ssl.checkserveridentity=true`. GreenMail also advertises `AUTH=XOAUTH2` and accepts any
token while auth is disabled, so the XOAUTH2 code path can be driven with a stub token
endpoint, but `OAuth2Properties.validate` requires an `https://` token URL, so that needs a
real TLS endpoint rather than a local HTTP stub.
