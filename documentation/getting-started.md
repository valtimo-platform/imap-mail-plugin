# Getting Started

Building and running this repository. For what the plugin does and how to configure it, go
to the [handleiding](handleiding.md); for how it works, the
[developer guide](developer-guide.md).

## Requirements

JDK 21 (see `.java-version`), Docker, and Node 20 or 22 — the frontend refuses to install on
newer Node.

## Building

```shell
# Backend: unit tests. Starts a Postgres container via docker-compose.
./gradlew :backend:plugin:test

# Frontend: build the plugin library.
cd frontend && npm install && npm run build
```

`backend/plugin` is the module that ships. `backend/app` is a sandbox Valtimo application
for trying the plugin out locally, and `frontend/projects/plugin` holds the configuration
UI.

## Trying it out

The plugin polls a mailbox on a schedule, so it needs a mailbox to talk to. The sandbox
brings one: a [GreenMail](https://greenmail-mail-test.github.io/greenmail/) container
preloaded with deliberately awkward fixtures, and a case that an incoming mail creates.

```shell
./backend/app/dev.sh up
./gradlew :backend:app:bootRun
./backend/app/dev.sh send new-request     # watch a case appear
```

See [Running the sandbox application](example-application.md) for the full startup sequence
including the frontend, and [backend/app/README.md](../backend/app/README.md) for what each
fixture exercises, the other post-processing actions and protocols, and how to reset between
runs.

## Pointing it at a real mailbox

Two things catch people out. A plain, non-TLS server needs `startTlsEnable` set to `false`.
To keep a particular node from touching any mailbox, set
`valtimo.imap-mail.polling-enabled=false`.

For background on plugins in general, see the
[Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition)
documentation.
