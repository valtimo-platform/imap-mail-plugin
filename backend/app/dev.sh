#!/usr/bin/env bash
#
# Entry point for the IMAP mail plugin sandbox. Run `./dev.sh help` for the commands.
#
# Everything here is a thin wrapper over docker compose and tools/mailctl.py. The point is not
# to hide compose but to spare you the flags: the compose file lives here in backend/app while
# you are usually standing in the repository root, the mail tooling runs in a container on the
# compose network, and the fixture paths are relative to that container rather than to you.

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$APP_DIR/../.." && pwd)"

MAILBOX="zaakpost@valtimo.local"
APP_URL="http://localhost:8080"

# Defaults mirror the ${..:-default} values in docker-compose.yml. backend/app/.env, if present, is
# read by compose; sourcing it here too keeps the messages this script prints truthful.
[[ -f "$APP_DIR/.env" ]] && set -a && . "$APP_DIR/.env" && set +a
IMAP_HOST_PORT="${IMAP_HOST_PORT:-3143}"
SMTP_HOST_PORT="${SMTP_HOST_PORT:-3025}"
GREENMAIL_API_HOST_PORT="${GREENMAIL_API_HOST_PORT:-8082}"
KEYCLOAK_HOST_PORT="${KEYCLOAK_HOST_PORT:-8081}"
VALTIMO_DB_HOST_PORT="${VALTIMO_DB_HOST_PORT:-54360}"
MINIO_HOST_PORT="${MINIO_HOST_PORT:-9000}"
MINIO_CONSOLE_HOST_PORT="${MINIO_CONSOLE_HOST_PORT:-9001}"

compose() {
    docker compose -f "$APP_DIR/docker-compose.yml" --project-directory "$APP_DIR" "$@"
}

# mailctl needs the compose network to reach greenmail by name, so it runs as a one-off
# container in the `tools` profile rather than on the host.
mailctl() {
    compose --progress quiet --profile tools run --rm --quiet-pull mail-tools "$@"
}

die() {
    printf 'dev.sh: %s\n' "$1" >&2
    exit 1
}

require_docker() {
    command -v docker >/dev/null || die "docker is not on PATH"
    docker info >/dev/null 2>&1 || die "the docker daemon is not running"
}

# Reports a port clash as a port clash. Compose's own message for this ("Bind for
# 0.0.0.0:54320 failed") names the port but not what to do about it, and on a machine that
# also runs the GZAC stack this is the failure you hit first.
check_ports() {
    command -v lsof >/dev/null || return 0

    # Ports this project already publishes are a restart, not a clash. Read from `docker ps`
    # rather than `docker compose ps`, whose Publishers template prints the published port as
    # a positional field with no "->" to anchor on.
    # Docker collapses adjacent published ports into a range - MinIO shows up as
    # "0.0.0.0:9000-9001->9000-9001/tcp", not as two entries - so the mappings are expanded
    # into one port per line before matching. Anchoring on ":$port->" alone missed every
    # port inside a range and reported our own running containers as a foreign clash.
    local ours
    ours=$(docker ps --filter "label=com.docker.compose.project=imap-mail-dev" --format '{{.Ports}}' 2>/dev/null |
        tr ',' '\n' |
        awk -F'->' '/->/ {
            split($1, address, ":")
            published = address[length(address)]
            if (split(published, range, "-") == 2) {
                for (port = range[1]; port <= range[2]; port++) print port
            } else {
                print published
            }
        }' || true)

    local blocked=()
    for entry in "$@"; do
        local port="${entry%%:*}"
        local label="${entry#*:}"
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            grep -qx "$port" <<<"$ours" || blocked+=("$port ($label)")
        fi
    done
    [[ ${#blocked[@]} -eq 0 ]] && return 0

    printf 'dev.sh: these host ports are already in use by something else:\n' >&2
    printf '  - %s\n' "${blocked[@]}" >&2
    cat >&2 <<EOF

Another stack is probably running - the GZAC compose stack uses 8081, 54320, 54329 and 5672.
Either stop it, or move this stack's ports by copying backend/app/ports.example to backend/app/.env.
If the port in question is 8081 and the other stack already serves a Valtimo realm there,
just reuse it: ./backend/app/dev.sh up --no-keycloak
EOF
    exit 1
}

cmd_up() {
    local with_keycloak=1 with_rabbitmq=0
    for arg in "$@"; do
        case "$arg" in
            --with-rabbitmq) with_rabbitmq=1 ;;
            --no-keycloak) with_keycloak=0 ;;
            *) die "unknown option for up: $arg" ;;
        esac
    done

    local profiles=()
    [[ $with_keycloak -eq 1 ]] && profiles+=(--profile keycloak)
    [[ $with_rabbitmq -eq 1 ]] && profiles+=(--profile rabbitmq)

    require_docker

    local ports=("$IMAP_HOST_PORT:IMAP" "$SMTP_HOST_PORT:SMTP" "$GREENMAIL_API_HOST_PORT:mail API" "$VALTIMO_DB_HOST_PORT:Valtimo database" "$MINIO_HOST_PORT:MinIO" "$MINIO_CONSOLE_HOST_PORT:MinIO console")
    [[ $with_keycloak -eq 1 ]] && ports+=("$KEYCLOAK_HOST_PORT:Keycloak")
    check_ports "${ports[@]}"

    echo "Starting the sandbox stack (this waits until every container reports healthy)..."
    compose "${profiles[@]+"${profiles[@]}"}" up -d --wait

    cat <<EOF

Sandbox is up.

  Mail server (IMAP)   localhost:$IMAP_HOST_PORT   mailbox $MAILBOX, any password
  Mail server (SMTP)   localhost:$SMTP_HOST_PORT
  Mail server API      http://localhost:$GREENMAIL_API_HOST_PORT/api/user
  Valtimo database     localhost:$VALTIMO_DB_HOST_PORT   plugin/password, database 'plugin'
  MinIO (S3)           http://localhost:$MINIO_HOST_PORT   bucket 'valtimo', minioadmin/minioadmin
  MinIO console        http://localhost:$MINIO_CONSOLE_HOST_PORT
EOF
    if [[ $with_keycloak -eq 1 ]]; then
        echo "  Keycloak             http://localhost:$KEYCLOAK_HOST_PORT/auth   (admin/admin)"
    else
        echo "  Keycloak             not started - the app still expects one on localhost:$KEYCLOAK_HOST_PORT"
    fi
    cat <<EOF

Preloaded fixtures in the mailbox:
EOF
    mailctl show INBOX 2>/dev/null | sed 's/^/  /' || echo "  (could not read the mailbox)"
    cat <<EOF

Next: start the application, which polls the mailbox every 15 seconds.

  cd $REPO_DIR && ./gradlew :backend:app:bootRun

Then open $APP_URL, or watch the log for lines from MailboxPollingService.
EOF
}

cmd_down() {
    require_docker
    local args=(down --remove-orphans)
    for arg in "$@"; do
        case "$arg" in
            --volumes | -v) args+=(--volumes) ;;
            *) die "unknown option for down: $arg" ;;
        esac
    done
    compose --profile tools --profile keycloak --profile rabbitmq "${args[@]}"
}

# The mailbox lives only in GreenMail's memory, so recreating the container is what gives you
# a clean mailbox reloaded from backend/app/fixtures/inbox. The Valtimo database is left alone on
# purpose: without --all you keep your cases and, more importantly, the imap_mail_processed
# claims - so a re-delivered fixture is still recognised as a duplicate.
cmd_reset() {
    require_docker
    if [[ "${1:-}" == "--all" ]]; then
        echo "Removing the whole stack including database volumes..."
        compose --profile tools --profile keycloak --profile rabbitmq down --volumes --remove-orphans
        cmd_up
        return
    fi
    [[ $# -eq 0 ]] || die "unknown option for reset: $1"

    echo "Recreating the mail server with the fixtures from backend/app/fixtures/inbox..."
    compose rm -sf greenmail >/dev/null
    compose up -d --wait greenmail
    mailctl show INBOX
    cat <<EOF

The Valtimo database was left untouched, so the claims in imap_mail_processed still exist and
these fixtures will be skipped as duplicates. Use './dev.sh reset --all' for a clean slate,
or './dev.sh unclaim' to drop the claims only.
EOF
}

# Drops the plugin's duplicate-detection rows without touching cases. This is the fastest way
# to make the preloaded fixtures processable again after they have already been handled once.
cmd_unclaim() {
    require_docker
    # Counted via RETURNING rather than read off psql's DELETE tag, which -q suppresses.
    local deleted
    deleted=$(compose exec -T valtimo-database \
        psql -qtAX -U plugin -d plugin \
        -c "WITH removed AS (DELETE FROM imap_mail_processed RETURNING 1) SELECT count(*) FROM removed;" 2>/dev/null) ||
        die "could not reach the database; is the stack up and has the application created its tables yet?"
    echo "Cleared ${deleted:-0} row(s) from imap_mail_processed."
    echo "Re-deliver the fixtures with './dev.sh reset' so they are unseen again."
}

cmd_send() {
    [[ $# -gt 0 ]] || die "send needs at least one fixture; try './dev.sh fixtures'"
    require_docker
    mailctl send "$@"
}

cmd_show() { require_docker && mailctl show "${1:-INBOX}"; }
cmd_unread() { require_docker && mailctl unread "${1:-INBOX}"; }
cmd_folders() { require_docker && mailctl folders; }

subject_of() {
    if command -v python3 >/dev/null; then
        python3 -c 'import email,email.header,sys
m = email.message_from_binary_file(open(sys.argv[1], "rb"))
print(email.header.make_header(email.header.decode_header(m.get("subject", "(none)"))))' "$1"
    else
        sed -n 's/^Subject: //p' "$1" | head -1
    fi
}

cmd_fixtures() {
    echo "Preloaded into $MAILBOX/INBOX when the mail server starts (backend/app/fixtures/inbox):"
    for file in "$APP_DIR"/fixtures/inbox/*.eml; do
        [[ -e "$file" ]] || continue
        printf '  %-28s %s\n' "$(basename "$file")" "$(subject_of "$file")"
    done
    echo
    echo "Sent on demand with './dev.sh send <name>' (backend/app/fixtures/send):"
    for file in "$APP_DIR"/fixtures/send/*.eml; do
        [[ -e "$file" ]] || continue
        printf '  %-28s %s\n' "$(basename "$file" .eml)" "$(subject_of "$file")"
    done
}

cmd_logs() {
    require_docker
    if [[ $# -eq 0 ]]; then
        compose --profile keycloak --profile rabbitmq logs -f --tail 50
    else
        compose logs -f --tail 50 "$@"
    fi
}

cmd_status() {
    require_docker
    compose --profile keycloak --profile rabbitmq ps
}

cmd_psql() {
    require_docker
    compose exec valtimo-database psql -U plugin -d plugin "$@"
}

cmd_help() {
    cat <<EOF
Sandbox for the IMAP mail plugin. The mail server is GreenMail, a fake; the Valtimo
application runs on the host with ./gradlew :backend:app:bootRun.

  ./dev.sh up [--no-keycloak] [--with-rabbitmq]
                                  start the stack and show the preloaded mailbox;
                                  --no-keycloak reuses a Keycloak another stack already runs
  ./dev.sh down [--volumes]       stop it; --volumes also wipes the databases
  ./dev.sh reset [--all]          fresh mailbox from the fixtures; --all also wipes the databases
  ./dev.sh unclaim                delete imap_mail_processed so fixtures can be handled again

  ./dev.sh fixtures               list the available fixtures and their subjects
  ./dev.sh send <name>...         send a fixture over SMTP, e.g. './dev.sh send new-request'
  ./dev.sh show [folder]          list the messages in a folder, with flags and Message-ID
  ./dev.sh unread [folder]        count unseen messages
  ./dev.sh folders                list the mailbox folders

  ./dev.sh status                 docker compose ps
  ./dev.sh logs [service]         follow logs, e.g. './dev.sh logs greenmail'
  ./dev.sh psql [args]            psql on the Valtimo database

See backend/app/README.md for what each fixture exercises and what to expect from it.
EOF
}

command="${1:-help}"
[[ $# -gt 0 ]] && shift

case "$command" in
    up) cmd_up "$@" ;;
    down) cmd_down "$@" ;;
    reset) cmd_reset "$@" ;;
    unclaim) cmd_unclaim "$@" ;;
    send) cmd_send "$@" ;;
    show | ls) cmd_show "$@" ;;
    unread) cmd_unread "$@" ;;
    folders) cmd_folders "$@" ;;
    fixtures) cmd_fixtures "$@" ;;
    logs) cmd_logs "$@" ;;
    status | ps) cmd_status "$@" ;;
    psql) cmd_psql "$@" ;;
    help | -h | --help) cmd_help ;;
    *) die "unknown command '$command'; try './dev.sh help'" ;;
esac
