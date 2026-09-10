# IMAP mail Plugin

Een externe mailbox uitlezen via IMAP of POP3 en per e-mail een dossier starten.

Deze plugin doet alleen de inkomende richting. Voor het versturen van e-mail zijn er de
[SMTP mail plugin](https://github.com/valtimo-platform/smtpmail-plugin) en de
[Microsoft Graph mail plugin](https://github.com/valtimo-platform/graph-mail-plugin); een
mailverwerkingsproces gebruikt deze plugin om te ontvangen en een van die twee om te antwoorden.

## Documentatie

Elk onderwerp staat op één plek. Weet je niet waar je moet zijn, begin dan bij de eerste
regel die bij je past.

| Document | Voor wie | Wat er in staat |
| --- | --- | --- |
| [Handleiding](documentation/handleiding.md) | beheerders | Een postbus koppelen: de instellingen in de beheerinterface, de proceskoppeling, en wat te doen als het niet werkt. Nederlands, geen techniek. |
| [Pluginreferentie](documentation/plugin.md) | beheerders, ontwikkelaars | Installatie, en de exacte namen, types en standaardwaarden van elke instelling, procesvariabele en limiet. Alleen tabellen. |
| [Ontwikkelaarsdocumentatie](documentation/developer-guide.md) | ontwikkelaars | Hoe de plugin intern werkt: pollen, transacties, duplicaatdetectie, MIME-verwerking, correlatie van antwoorden. |
| [Aan de slag](documentation/getting-started.md) | ontwikkelaars | Deze repository bouwen en draaien. |
| [Sandbox draaien](documentation/example-application.md) | ontwikkelaars | De opstartvolgorde van de sandboxapplicatie. |
| [Sandbox](backend/app/README.md) | ontwikkelaars | De nepmailserver, de fixtures, `dev.sh` en het voorbeelddossier. |
| [Release-notities](documentation/release-notes.md) | iedereen | Versiegeschiedenis en wijzigingen. |

## Snel proberen

```shell
./backend/app/dev.sh up                # nepmailserver met fixtures, database, Keycloak
./gradlew :backend:app:bootRun         # start de sandboxapplicatie
./backend/app/dev.sh send new-request  # stuur een e-mail; er verschijnt een dossier
```

## Contact

Klaas Schuijtemaker (Ritense)
