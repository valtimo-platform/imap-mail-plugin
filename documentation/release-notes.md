# Release notes

Overzicht van wijzigingen per versie van de IMAP mail plugin.

## 0.0.1

Eerste opzet:

- Mailbox uitlezen via IMAP en POP3 (`imaps`, `imap`, `pop3s`, `pop3`).
- Authenticatie met gebruikersnaam en wachtwoord of met OAuth2 (XOAUTH2), zodat de plugin ook
  tegen Microsoft 365 werkt.
- `receive-mail` procesverbinding op een message start event, receive task of intermediate
  catch event, met optionele filters op afzender, onderwerp en ontvanger.
- Bericht en bijlagen worden in de temporary resource storage geplaatst en als resource-id's
  als procesvariabele doorgegeven.
- Verwerkte e-mail wordt bijgehouden in `imap_mail_processed`, zodat meerdere nodes dezelfde
  mailbox kunnen pollen zonder dubbele zaken.
- Instelbare nabewerking: markeren als gelezen, verplaatsen, verwijderen of niets doen.
