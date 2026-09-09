# Example Application

This project also contains a working example application which is meant to showcase the plugin.

It deploys one case definition, `mail-intake`, whose process is started by an incoming mail and
resumed by a reply. The mailbox it reads is a fake mail server defined in the same module. See
[backend/app/README.md](../backend/app/README.md) for the mailbox, the fixtures, and the
process links that wire the two together.

## Running the example application

All commands below should be run from the **project root** directory.

### Prerequisites

- Java 21
- [Docker (Desktop)](https://www.docker.com/products/docker-desktop/)

### Start docker

Make sure docker is running, then start the sandbox stack — mail server, database and
Keycloak:

```shell
./backend/app/dev.sh up
```

`./gradlew :backend:app:composeUp` starts the same stack, and `bootRun` depends on it, so
this step is optional. `dev.sh` is the friendlier entry point: it waits for health, reports
port conflicts in terms of what to do about them, and prints the mailbox contents.

If another stack already serves a Valtimo realm on port 8081 — the GZAC stack does — reuse it
rather than starting a second Keycloak:

```shell
./backend/app/dev.sh up --no-keycloak
COMPOSE_PROFILES= ./gradlew :backend:app:bootRun
```

### Start backend

By gradle script:

```shell
./gradlew :backend:app:bootRun
```

### Start frontend

```shell
nvm use 20
npm run clean
npm install
npm run build
npm start
```

### Keycloak users

The example application has a few test users that are preconfigured.

| Name         | Role           | Username  | Password  |
|--------------|----------------|-----------|-----------|
| James Vance  | ROLE_USER      | user      | user      |
| Asha Miller  | ROLE_ADMIN     | admin     | admin     |
| Morgan Finch | ROLE_DEVELOPER | developer | developer |

## Source code

The source code is split up into two modules:

1. [Frontend](/frontend)
2. [Backend](/backend)
