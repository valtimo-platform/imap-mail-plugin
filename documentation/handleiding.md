# Handleiding

Voor beheerders die een postbus aan Valtimo willen koppelen. Je hebt hiervoor geen
programmeerkennis nodig; alles in deze handleiding gebeurt in de beheerinterface en in de
procesmodelleur.

Zoek je de technische werking, dan staat die in de
[ontwikkelaarsdocumentatie](developer-guide.md). Zoek je de precieze naam of het type van
een instelling, dan staan die in de [pluginreferentie](plugin.md).

## Wat deze plugin doet

De plugin leest een e-mailpostbus uit en maakt van elke binnengekomen e-mail een dossier —
of laat een lopend dossier verdergaan als de e-mail een antwoord is.

Er komt geen handeling van jou aan te pas. De plugin kijkt zelf periodiek in de postbus (in
de standaardinstelling elke vijf minuten). Wat hij daar vindt gaat als nieuw dossier de
organisatie in, met afzender, onderwerp, datum, de tekst van de e-mail en de bijlagen erbij.

Wat de plugin **niet** doet is e-mail versturen. Wil je vanuit een proces antwoorden, dan
gebruik je daarvoor de
[SMTP mail plugin](https://github.com/valtimo-platform/smtpmail-plugin) of de
[Microsoft Graph mail plugin](https://github.com/valtimo-platform/graph-mail-plugin). Een
mailverwerkingsproces gebruikt deze plugin om te ontvangen en een van die twee om te
antwoorden.

## Voordat je begint

Regel deze dingen eerst; zonder deze gegevens kom je halverwege stap 1 vast te zitten.

| Wat je nodig hebt | Wie levert dat meestal |
| --- | --- |
| Het adres van de mailserver, bijvoorbeeld `outlook.office365.com` | de beheerder van de mailomgeving |
| Het volledige e-mailadres van de postbus | de beheerder van de mailomgeving |
| Een wachtwoord, óf — bij Microsoft 365 — de OAuth2-gegevens uit [Microsoft 365](#microsoft-365) | de beheerder van de mailomgeving |
| Een proces waarin de e-mail terecht moet komen | de procesontwerper |

Gebruik bij voorkeur een postbus die alléén voor dit doel bestaat. De plugin markeert
verwerkte e-mail standaard als gelezen, en dat is verwarrend in een postbus waar ook mensen
in werken.

## Stap 1 — De postbus instellen

Ga in de beheerinterface naar **Plugins** en kies **Plugin configureren**. Kies bij
*Kies je plugin* de tegel **IMAP mail**. Je krijgt dan een formulier met de onderstaande
velden. Bewaar met **Configuratie opslaan**.

### Verbinding

| Veld | Wat je invult |
| --- | --- |
| **Configuratienaam** | Een naam die jij herkent, bijvoorbeeld `Postbus bezwaren`. Deze naam kies je later bij het koppelen aan een processtap. |
| **Protocol** | Vrijwel altijd *IMAP over TLS*. Kies alleen POP3 als de mailserver niets anders kan: POP3 kent geen mappen en geen gelezen-markering, waardoor de plugin verwerkte e-mail alleen kan verwijderen. |
| **Server** | Het adres van de mailserver. |
| **Poort** | Laat leeg. De plugin vult dan de standaardpoort van het gekozen protocol in. |
| **Gebruikersnaam** | Meestal het volledige e-mailadres van de postbus. |

### Authenticatie

Bij **Gebruikersnaam en wachtwoord** verschijnt een wachtwoordveld. Meer is er niet.

Bij **OAuth2 / XOAUTH2** verschijnen vier velden: het token-endpoint, een client-id, een
client secret en een scope. Die krijg je van degene die de app-registratie aanmaakt — zie
[Microsoft 365](#microsoft-365) hieronder. De scope mag je leeg laten voor Microsoft 365.

Kies je een protocol zonder ingebouwde versleuteling (*IMAP met STARTTLS* of *POP3 met
STARTTLS*), dan verschijnt daarnaast het vinkje **STARTTLS gebruiken**. Laat dat aan staan.
Zet je het uit, dan gaan de inloggegevens onversleuteld over de lijn; doe dat alleen op een
testomgeving.

### Wat er met verwerkte e-mail gebeurt

Bij **Map** vul je in welke map gelezen moet worden. Meestal is dat `INBOX`.

**Na verwerking** bepaalt wat er met een e-mail gebeurt zodra het dossier is aangemaakt. Dit
is de belangrijkste rem op dubbel werk: een e-mail die gelezen, verplaatst of verwijderd is,
komt de volgende ronde niet opnieuw langs.

| Keuze | Wat er gebeurt | Wanneer kies je dit |
| --- | --- | --- |
| **Markeren als gelezen** | De e-mail blijft staan en krijgt de gelezen-markering. | De standaard, en in bijna alle gevallen de juiste keuze. Werkt niet met POP3. |
| **Verplaatsen naar een andere map** | De e-mail gaat naar de map die je bij **Doelmap** invult. | Als je een postbus overzichtelijk wilt houden of verwerkte post wilt archiveren. Werkt niet met POP3. |
| **Verwijderen** | De e-mail wordt definitief verwijderd. | Als de mailserver alleen POP3 kan, of als bewaren onwenselijk is. Er is geen weg terug. |
| **Niets doen (alleen om te testen)** | De postbus blijft onaangeroerd. | Alleen om te proberen. De plugin ziet de e-mail elke ronde opnieuw en houdt alleen in de database bij dat hij al verwerkt is — en dat wordt na verloop van tijd opgeruimd. Niet gebruiken in productie. |

Kies je **Verplaatsen**, dan verschijnt het veld **Doelmap**. Die moet een andere map zijn
dan bij **Map**. Bestaat de map nog niet, dan maakt de plugin hem aan.

### De laatste twee velden

**Maximum aantal e-mails per ronde** begrenst hoeveel e-mail er per keer wordt opgehaald;
de rest volgt vanzelf de ronde erna. De standaard van 25 voldoet bijna altijd. Verhoog dit
alleen als er structureel meer binnenkomt dan er wordt weggewerkt.

**Debuglogging** schrijft het volledige mailverkeer weg in de logging: afzenders, headers
én de volledige inhoud van elk bericht. Inkomende post bevat vrijwel altijd
persoonsgegevens, dus zet dit alleen tijdelijk aan om een probleem te onderzoeken, en daarna
weer uit.

## Stap 2 — Het proces koppelen

De postbus wordt pas uitgelezen als er minstens één processtap naar verwijst. Een
configuratie waar niets aan gekoppeld is, wordt nooit geopend. Dat is met opzet: je kunt een
postbus alvast instellen zonder dat er iets mee gebeurt.

Open het proces in de procesmodelleur, klik de processtap aan en kies **Proceskoppeling
aanmaken**. Kies daar de plugin-configuratie uit stap 1 en de actie **E-mail ontvangen**.

Welke soort processtap je aanklikt, bepaalt wat de e-mail doet:

| Processtap | Wat een binnenkomende e-mail doet |
| --- | --- |
| **Message start event** | Start een nieuw dossier. Dit is de gewone koppeling. |
| **Receive task** | Laat een dossier dat op deze stap wacht verdergaan, als de e-mail een antwoord is op een eerdere mail uit dát dossier. |
| **Intermediate catch event** | Hetzelfde, voor een tussentijds antwoord. |

### Filters

Standaard pakt een koppeling élke e-mail uit de postbus op. Dat is precies wat je wilt als
de postbus maar één proces voedt.

Voedt één postbus meerdere processen, dan geef je elke koppeling een eigen filter:

| Veld | Betekenis |
| --- | --- |
| **Afzender bevat** | Een stukje van het e-mailadres van de afzender, bijvoorbeeld `@gemeente.nl`. |
| **Onderwerp bevat** | Een stukje van de onderwerpregel, bijvoorbeeld `Bezwaar`. |
| **Ontvanger bevat** | Een stukje van een adres in de **Aan-regel**, bijvoorbeeld `bezwaar@gemeente.nl`. Let op: alleen de Aan-regel telt — staat de postbus in de cc of is de e-mail via een alias binnengekomen die niet in de Aan-regel staat, dan past het filter niet. |

Let bij het bedenken van filters op twee dingen:

- Hoofdletters maken niet uit, maar ingevulde velden gelden **allemaal tegelijk**. Vul je
  onderwerp én afzender in, dan moet een e-mail aan beide voldoen.
- Je kunt alleen zeggen wat er *wel* in moet staan, niet wat er *niet* in mag staan. Wil je
  aanvragen en antwoorden uit elkaar houden, filter dan op de ontvanger — bijvoorbeeld een
  apart alias voor antwoorden. Op het onderwerp lukt dat niet, omdat `Re: Bezwaar` het woord
  `Bezwaar` gewoon bevat.

## Stap 3 — Controleren of het werkt

Stuur een e-mail naar de postbus en wacht één ronde af (standaard vijf minuten). Er hoort
een dossier te verschijnen.

Gebeurt er niets, loop dan deze lijst langs — het is vrijwel altijd een van deze vier:

| Wat je ziet | Waarschijnlijke oorzaak | Wat je doet |
| --- | --- | --- |
| Er verschijnt geen dossier en de e-mail blijft ongelezen | Er is geen processtap aan de configuratie gekoppeld, dus de postbus wordt niet geopend | Doe stap 2 |
| De e-mail is wél gelezen, maar er is geen dossier | Het filter op de koppeling sluit deze e-mail uit | Controleer de drie filtervelden; laat ze leeg om alles op te pakken |
| Niets werkt en de logging noemt inloggen of certificaten | Verkeerd wachtwoord, of een Microsoft 365-postbus zonder OAuth2 | Zie [Microsoft 365](#microsoft-365) |
| Een antwoord maakt een nieuw dossier in plaats van het bestaande te vervolgen | Het antwoord is aan de koppeling van het *message start event* blijven hangen | Geef aanvragen en antwoorden verschillende ontvangeradressen en filter daarop |

Blijft het onduidelijk, zet dan **Debuglogging** tijdelijk aan en kijk mee in de logging.
Vergeet niet het daarna weer uit te zetten.

## Wat er in het dossier terechtkomt

Bij elke e-mail legt de plugin het volgende vast: de afzender (adres en, als de e-mail die
meestuurt, de naam), de geadresseerden en cc-geadresseerden, het onderwerp, het moment van
verzenden en van ontvangen, en de tekst van het bericht plus de bijlagen.

Of dat ook allemaal *zichtbaar* wordt in het dossier, bepaalt de procesontwerper. De plugin
levert de gegevens aan; het proces bepaalt welke ervan in het dossier worden overgenomen en
op welk tabblad ze verschijnen. Zie je een gegeven niet terug dat je wel verwacht, dan is
dat een vraag aan de procesontwerper, niet een instelling in deze plugin.

Twee grenzen zijn vast ingesteld: de tekst van een bericht mag maximaal 10 MB zijn en de
bijlagen samen maximaal 25 MB. Een e-mail die daaroverheen gaat wordt niet verwerkt en
blijft op de server staan.

Dezelfde e-mail levert nooit twee dossiers op, ook niet als er meerdere Valtimo-servers
tegelijk in dezelfde postbus kijken.

## Antwoorden en vervolgmail

Een antwoord komt terug in het dossier dat de oorspronkelijke e-mail heeft verstuurd — niet
in een willekeurig ander dossier dat op hetzelfde punt staat te wachten.

Dat werkt doordat mailprogramma's bij een antwoord automatisch bijhouden op welk bericht
wordt gereageerd. De plugin gebruikt dat spoor om het juiste dossier te vinden. Daar zijn
twee gevolgen aan verbonden die je bij het inrichten moet weten:

- Het werkt alleen bij een **echt antwoord**. Stuurt iemand een nieuwe e-mail met hetzelfde
  onderwerp in plaats van op de oude te antwoorden, dan is dat spoor er niet en gebeurt er
  niets met het wachtende dossier.
- Een e-mail zonder dat spoor kan een *receive task* of *intermediate catch event* dus nooit
  in beweging krijgen. Moet een losse e-mail iets kunnen starten, gebruik dan een
  **message start event**.

## Microsoft 365

Microsoft heeft inloggen met gebruikersnaam en wachtwoord voor IMAP en POP3 uitgezet. Een
Microsoft 365-postbus werkt daarom alleen met **OAuth2 / XOAUTH2**.

Vraag de Azure-beheerder om een app-registratie en om deze vier dingen:

1. Het token-endpoint. Dat heeft de vorm
   `https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token`, met de tenant-id van
   de organisatie erin.
2. De client-id van de app-registratie.
3. Een client secret.
4. De rechten: de app-registratie heeft de **applicatiepermissie `IMAP.AccessAsApp`** nodig,
   met beheerderstoestemming, en de service principal moet toegang tot déze postbus hebben.

De scope laat je in Valtimo leeg. Punt 4 wordt het vaakst vergeten: zonder die permissie
lijkt alles goed ingevuld en mislukt het inloggen alsnog.

## Meer lezen

- [Pluginreferentie](plugin.md) — alle instellingen met hun technische naam, type en
  standaardwaarde
- [Ontwikkelaarsdocumentatie](developer-guide.md) — hoe de plugin intern werkt
- [Release-notities](release-notes.md) — wat er per versie is veranderd
