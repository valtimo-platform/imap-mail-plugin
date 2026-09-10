# Running the sandbox application

`backend/app` is a throwaway Valtimo application for exercising the plugin locally. It
deploys one case definition, `mail-intake`, whose process is started by an incoming mail and
resumed by a reply, and it reads a fake mail server defined in the same module.

This page is the startup sequence, nothing more.
[backend/app/README.md](../backend/app/README.md) is the sandbox's own documentation: what
is running and on which ports, the case and its process links, what each fixture exercises,
how to reset between runs, and the behaviour worth watching.

## Prerequisites

- Java 21
- [Docker (Desktop)](https://www.docker.com/products/docker-desktop/)
- Node 20 or 22

## Start docker

From the repository root, with docker running:

```shell
./backend/app/dev.sh up
```

`./gradlew :backend:app:composeUp` starts the same stack, and `bootRun` depends on it, so
this step is optional. `dev.sh` is the friendlier entry point: it waits for health, reports
port conflicts in terms of what to do about them, and prints the mailbox contents. If a port
is already taken — the GZAC stack claims several — it says so and tells you what to do; see
the port conflicts section of the sandbox README.

## Start the backend

From the repository root:

```shell
./gradlew :backend:app:bootRun
```

## Start the frontend

From `frontend/`, not the repository root — there is no package.json above it:

```shell
nvm use 20
npm install
npm run build     # builds the plugin library the application imports
npm start
```

`npm run clean` first if a previous install is in a bad state; it removes `node_modules`,
`dist`, `.angular` and the lockfile, so it is a repair step rather than part of the routine.

## Keycloak users

The sandbox realm has three preconfigured users:

| Name         | Role           | Username  | Password  |
|--------------|----------------|-----------|-----------|
| James Vance  | ROLE_USER      | user      | user      |
| Asha Miller  | ROLE_ADMIN     | admin     | admin     |
| Morgan Finch | ROLE_DEVELOPER | developer | developer |

## Source code

| Module | |
| --- | --- |
| [backend/plugin](/backend/plugin) | the plugin; the only module that ships |
| [backend/app](/backend/app) | this sandbox application |
| [frontend](/frontend) | the Angular configuration UI, and a host application to run it in |
