# Getting Started

## Development

Requirements: JDK 21 (see `.java-version`), Docker, and Node 20 or 22 — the frontend refuses
to install on newer Node.

```shell
# Backend: unit tests. Starts a Postgres container via docker-compose.
./gradlew :backend:plugin:test

# Frontend: build the plugin library.
cd frontend && npm install && npm run build
```

The backend module is `backend/plugin`; `backend/app` is a sandbox Valtimo application for
trying the plugin out locally. See [Example Application](example-application.md).

## Trying it out

The plugin polls a mailbox on a schedule, so it needs a mailbox to talk to. The sandbox
application in `backend/app` brings one: a
[GreenMail](https://greenmail-mail-test.github.io/greenmail/) container preloaded with
fixtures, and a Valtimo case that an incoming mail creates.

```shell
./backend/app/dev.sh up
./gradlew :backend:app:bootRun
./backend/app/dev.sh send new-request     # watch a case appear
```

See [backend/app/README.md](../backend/app/README.md) for what each fixture exercises, how to
try the other post-processing actions and protocols, and how to reset between runs.

To point the plugin at a mailbox of your own instead, note that a plain (non-TLS) server
needs `startTlsEnable` set to `false`. To keep a mailbox from being touched at all on a
particular node, set `valtimo.imap-mail.polling-enabled=false`.

## Configuration reference

See the [plugin documentation](plugin.md) for every configuration property, the `receive-mail`
process link, the process variables the plugin sets, and how duplicate detection works.

For background on plugins in general, see the
[Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition)
documentation.
