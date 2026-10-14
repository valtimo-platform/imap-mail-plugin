# Release notes

Overzicht van wijzigingen per versie van de IMAP mail plugin.

## 0.0.7

- Nieuwe actie **Vervolgen bij e-mail** (`resume-on-mail`) voor een receive task of
  intermediate catch event. Die vervolgt een wachtend dossier met elke e-mail die door het
  filter komt, ook als het geen antwoord is. De filtervelden mogen naar het dossier
  verwijzen, zoals `pv:aanvragerEmail` of `doc:/zaaknummer`; de plugin vult die per wachtend
  dossier in, zodat elk dossier alleen zijn eigen e-mail krijgt.
- **E-mail ontvangen** (`receive-mail`) werkt ongewijzigd. Bestaande koppelingen hoeven niets
  aan te passen.

## 0.0.6

Ondersteuning voor Valtimo 13.48.0.

## 0.0.5

- De procesvariabele 'attachments' is nu beschikbaar als lijst van objecten, met properties fileName, contentType, sizeInBytes en resourceId.
- Default interval voor het pollen is nu 1 minuut.

## 0.0.4

Correctie, geen wijzigingen in instellingen of procesvariabelen:

- Een proces met méér dan één startgebeurtenis werd op de verkeerde plek gestart. De plugin
  startte het proces op zijn sleutel, waarna de procesmotor binnenkomt bij de startgebeurtenis
  die hij als eerste beschouwt — en dat is de gewone startgebeurtenis (het startformulier),
  niet de berichtstartgebeurtenis waaraan de `receive-mail`-koppeling hangt. De zaak ontstond
  dan wel, maar de uitvoeringslisteners van de mailstartgebeurtenis liepen nooit, zodat er
  niets van de e-mail op de zaak terechtkwam. De plugin correleert nu het startbericht van
  precies het element waaraan de koppeling hangt.
- Voor een proces met één startgebeurtenis verandert er niets: dat was en blijft de
  gebeurtenis waar de zaak begint.

## 0.0.3

- Een e-mail met zowel een platte-tekst- als een HTML-versie wordt voortaan in beide
  formaten opgeslagen, via de nieuwe procesvariabelen `mailBodyTextResourceId` en
  `mailBodyHtmlResourceId`. Eerder werd alleen de gekozen versie bewaard en was de andere
  verloren; welke van de twee een proces nodig heeft — de HTML voor een antwoord, de platte
  tekst voor analyse — kan de plugin niet weten. Een variabele wordt alleen gezet als de
  e-mail dat formaat had.
- `mailBodyResourceId` en `mailBodyIsHtml` blijven ongewijzigd: die wijzen nog steeds naar de
  versie die de plugin zelf zou kiezen. Bestaande processen hoeven niets aan te passen.

## 0.0.2

Correcties, geen wijzigingen in instellingen of procesvariabelen:

- Een e-mail met meer dan 100 bijlagen wordt geweigerd. De bestaande grens van 25 MB zegt
  niets over het *aantal* bijlagen — een lege bijlage weegt immers niets — waardoor een
  bericht met tienduizenden lege bijlagen die grens ongemoeid liet.
- Een geweigerde e-mail (te groot, of te veel bijlagen) blijft niet meer op de server staan.
  Dat gebeurde wel, waardoor zo'n bericht bij elke ronde opnieuw werd opgehaald en opnieuw
  geweigerd, en het een plek innam die een gewone e-mail nodig had. Nu wordt het afgehandeld
  zoals een verwerkt bericht, met een foutmelding in het logboek.
- Een verbinding zonder versleuteling (`imap` of `pop3` met STARTTLS uit) geeft nu een
  waarschuwing in het logboek. Het blijft toegestaan, maar niet langer stilzwijgend.

## 0.0.1

Eerste opzet:

- Mailbox uitlezen via IMAP en POP3 (`imaps`, `imap`, `pop3s`, `pop3`) en meer
