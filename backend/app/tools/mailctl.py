#!/usr/bin/env python3
"""Drives the sandbox mail server: send fixtures, inspect the mailbox, manage folders.

Runs inside the `mail-tools` container so it needs nothing installed on the host - the
standard library covers both SMTP and IMAP. Invoke it through `../dev.sh`, which supplies
the container and the network.

Fixtures are sent as raw bytes rather than being rebuilt from parsed fields, so whatever a
.eml file says on disk - including a deliberately absent Message-ID, an odd
Content-Disposition or a hostile filename - is exactly what the plugin has to deal with.
"""

import argparse
import email
import email.header
import email.policy
import imaplib
import os
import smtplib
import sys
from email.utils import getaddresses

HOST = os.environ.get("GREENMAIL_HOST", "localhost")
SMTP_PORT = int(os.environ.get("GREENMAIL_SMTP_PORT", "3025"))
IMAP_PORT = int(os.environ.get("GREENMAIL_IMAP_PORT", "3143"))
MAILBOX = os.environ.get("MAILBOX", "zaakpost@valtimo.local")

# GreenMail runs with -Dgreenmail.auth.disabled, so the password is never checked. A real
# value is passed anyway to keep the exchange identical to an authenticating server.
PASSWORD = os.environ.get("MAILBOX_PASSWORD", "zaakpost")


def _fail(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def _resolve(path):
    """Accepts a bare fixture name as well as a path, so `send reply-aanvraag` works."""
    candidates = [path]
    if not path.endswith(".eml"):
        candidates.append(f"{path}.eml")
    for candidate in list(candidates):
        for directory in ("/fixtures/send", "/fixtures/inbox"):
            candidates.append(os.path.join(directory, os.path.basename(candidate)))
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    _fail(f"no such fixture: {path} (looked in /fixtures/send and /fixtures/inbox)")


def _envelope_recipients(message):
    """Envelope recipients come from To and Cc, unless the fixture overrides them.

    `X-Fixture-Envelope-To` exists because envelope and header recipients are allowed to
    disagree, and the plugin's recipient filter reads the header. A fixture that needs to
    arrive in one mailbox while claiming to be addressed to another - an alias, a plus-address,
    a catch-all - says so with that header, and it is left in the message so the mail still
    documents itself.

    Bcc is ignored: a fixture that wants delivery to an address no header mentions should use
    the override or --to.
    """
    override = message.get_all("x-fixture-envelope-to", [])
    if override:
        return [address for _, address in getaddresses(override) if address]
    headers = message.get_all("to", []) + message.get_all("cc", [])
    return [address for _, address in getaddresses(headers) if address]


def send(args):
    with smtplib.SMTP(HOST, SMTP_PORT, timeout=30) as smtp:
        for path in args.fixtures:
            resolved = _resolve(path)
            with open(resolved, "rb") as handle:
                raw = handle.read()

            parsed = email.message_from_bytes(raw, policy=email.policy.default)
            sender = args.sender or next(
                (address for _, address in getaddresses(parsed.get_all("from", []))),
                "fixture@example.org",
            )
            recipients = args.to or _envelope_recipients(parsed)
            if not recipients:
                _fail(f"{resolved} has no To or Cc header; pass --to explicitly")

            smtp.sendmail(sender, recipients, raw)
            subject = parsed.get("subject", "(no subject)")
            print(f"sent {os.path.basename(resolved)} -> {', '.join(recipients)}  [{subject}]")


def _imap():
    client = imaplib.IMAP4(HOST, IMAP_PORT)
    client.login(MAILBOX, PASSWORD)
    return client


def _decode(value):
    if value is None:
        return "(none)"
    return str(email.header.make_header(email.header.decode_header(value)))


def show(args):
    client = _imap()
    try:
        status, _ = client.select(args.folder, readonly=True)
        if status != "OK":
            _fail(f"folder '{args.folder}' does not exist; try `folders`")

        status, data = client.search(None, "ALL")
        numbers = data[0].split() if data and data[0] else []
        print(f"{MAILBOX} / {args.folder}: {len(numbers)} message(s)")
        if not numbers:
            return

        for number in numbers:
            # BODY.PEEK avoids setting \Seen, which would otherwise make simply looking at
            # the mailbox change what the next poll considers a candidate.
            status, data = client.fetch(
                number, "(UID FLAGS BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT MESSAGE-ID DATE)])"
            )
            meta = data[0][0].decode("utf-8", "replace") if data and data[0] else ""
            headers = email.message_from_bytes(
                data[0][1] if data and data[0] and len(data[0]) > 1 else b""
            )
            uid = meta.split("UID ")[1].split()[0].rstrip(")") if "UID " in meta else "?"
            flags = meta.split("FLAGS (")[1].split(")")[0] if "FLAGS (" in meta else ""
            print(f"  #{number.decode()} uid={uid} flags=[{flags}]")
            print(f"     from:       {_decode(headers.get('from'))}")
            print(f"     to:         {_decode(headers.get('to'))}")
            print(f"     subject:    {_decode(headers.get('subject'))}")
            print(f"     message-id: {headers.get('message-id') or '(absent - plugin falls back to the IMAP UID)'}")
    finally:
        client.logout()


def folders(args):
    client = _imap()
    try:
        status, data = client.list()
        for line in data or []:
            print("  " + line.decode("utf-8", "replace"))
    finally:
        client.logout()


def unread(args):
    client = _imap()
    try:
        client.select(args.folder, readonly=True)
        status, data = client.search(None, "UNSEEN")
        numbers = data[0].split() if data and data[0] else []
        print(f"{len(numbers)} unseen message(s) in {args.folder}")
    finally:
        client.logout()


def main():
    parser = argparse.ArgumentParser(prog="mailctl", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    send_parser = sub.add_parser("send", help="send one or more .eml fixtures over SMTP")
    send_parser.add_argument("fixtures", nargs="+", help="fixture name or path")
    send_parser.add_argument("--to", action="append", help="override the envelope recipient")
    send_parser.add_argument("--sender", help="override the envelope sender")
    send_parser.set_defaults(func=send)

    show_parser = sub.add_parser("show", help="list the messages in a folder")
    show_parser.add_argument("folder", nargs="?", default="INBOX")
    show_parser.set_defaults(func=show)

    unread_parser = sub.add_parser("unread", help="count unseen messages in a folder")
    unread_parser.add_argument("folder", nargs="?", default="INBOX")
    unread_parser.set_defaults(func=unread)

    folders_parser = sub.add_parser("folders", help="list the folders in the mailbox")
    folders_parser.set_defaults(func=folders)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
