# IMAP mail Plugin

Een externe mailbox uitlezen via IMAP of POP3 en per e-mail een zaak starten.

Deze plugin doet alleen de inkomende richting. Voor het versturen van e-mail zijn er de
[SMTP mail plugin](https://github.com/valtimo-platform/smtpmail-plugin) en de
[Microsoft Graph mail plugin](https://github.com/valtimo-platform/graph-mail-plugin); een
mailverwerkingsproces gebruikt deze plugin om te ontvangen en een van die twee om te antwoorden.

## Documentatie

- [Aan de slag](documentation/getting-started.md) — installatie, gebruik en ontwikkelinstructies
- [Plugin documentatie](documentation/plugin.md) — pluginsdetails en configuratie
- [Voorbeeldapplicatie](documentation/example-application.md) — de sandbox lokaal draaien
- [Sandbox](backend/app/README.md) — nepmailserver, fixtures en `backend/app/dev.sh`
- [Release-notities](documentation/release-notes.md) — versiegeschiedenis en wijzigingen

## Snel proberen

```shell
./backend/app/dev.sh up                # nepmailserver met fixtures, database, Keycloak
./gradlew :backend:app:bootRun         # start de sandboxapplicatie
./backend/app/dev.sh send new-request  # stuur een e-mail; er verschijnt een zaak
```

## Contact

Klaas Schuijtemaker (Ritense)
