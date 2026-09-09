/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {PluginSpecification} from "@valtimo/plugin";
import {ImapMailPluginConfigurationComponent} from "./components/imap-mail-plugin-configuration/imap-mail-plugin-configuration.component";
import {ReceiveMailConfigurationComponent} from "./components/receive-mail-configuration/receive-mail-configuration.component";
import {IMAP_MAIL_PLUGIN_LOGO_BASE64} from "./assets";

/** `pluginId` must equal the `key` of the `@Plugin` annotation on `ImapMailPlugin`. */
const imapMailPluginSpecification: PluginSpecification = {
  pluginId: "imap-mail",
  pluginConfigurationComponent: ImapMailPluginConfigurationComponent,
  pluginLogoBase64: IMAP_MAIL_PLUGIN_LOGO_BASE64,
  functionConfigurationComponents: {
    "receive-mail": ReceiveMailConfigurationComponent,
  },
  pluginTranslations: {
    nl: {
      title: "IMAP mail",
      description: "Een externe mailbox uitlezen via IMAP of POP3 en per e-mail een zaak starten.",
      configurationTitle: "Configuratienaam",

      protocol: "Protocol",
      protocolTooltip:
        "IMAP kan mappen gebruiken en berichten als gelezen markeren; POP3 kan alleen de inbox lezen en berichten verwijderen.",
      host: "Server",
      port: "Poort",
      portTooltip: "Laat leeg om de standaardpoort van het gekozen protocol te gebruiken.",
      username: "Gebruikersnaam",
      usernameTooltip: "Meestal het volledige e-mailadres van de postbus.",

      authentication: "Authenticatie",
      authenticationTooltip:
        "Microsoft 365 staat geen wachtwoord meer toe voor IMAP en POP3; kies daar OAuth2. Voor Exchange op eigen servers, Dovecot en Zimbra werkt een wachtwoord doorgaans wel.",
      password: "Wachtwoord",
      oauthTokenUrl: "OAuth2 token-endpoint",
      oauthTokenUrlTooltip:
        "De volledige URL van het token-endpoint. Voor Microsoft 365: https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token",
      oauthClientId: "OAuth2 client-id",
      oauthClientSecret: "OAuth2 client secret",
      oauthScope: "OAuth2 scope",
      oauthScopeTooltip:
        "Laat leeg voor Microsoft 365. De app-registratie heeft daarbij de IMAP.AccessAsApp-rechten nodig.",
      startTlsEnable: "STARTTLS gebruiken",
      startTlsEnableTooltip:
        "Verplicht STARTTLS op de onbeveiligde poort. Zonder STARTTLS gaan de inloggegevens onversleuteld over de lijn.",

      folder: "Map",
      postProcessAction: "Na verwerking",
      postProcessActionTooltip:
        "Wat er met een e-mail gebeurt zodra er een proces voor is gestart. Dit voorkomt dat dezelfde e-mail bij de volgende ronde opnieuw wordt opgehaald.",
      targetFolder: "Doelmap",
      maxMessagesPerPoll: "Maximum aantal e-mails per ronde",
      maxMessagesPerPollTooltip:
        "Begrenst hoeveel e-mails er per keer worden verwerkt. De rest volgt in de volgende ronde.",
      debug: "Debuglogging",
      debugTooltip:
        "Schrijft het volledige protocolverkeer weg, inclusief afzenders, headers en berichtinhoud. Alleen aanzetten om een probleem te onderzoeken.",

      "receive-mail": "E-mail ontvangen",
      actionDescription:
        "Start of vervolgt dit proces voor elke e-mail uit de mailbox. Laat de velden leeg om alle e-mail te verwerken, of vul een filter in om alleen bepaalde e-mail door dit proces te laten oppakken.",
      senderContains: "Afzender bevat",
      senderContainsTooltip: "Filtert op een deel van het e-mailadres van de afzender.",
      subjectContains: "Onderwerp bevat",
      subjectContainsTooltip: "Filtert op een deel van de onderwerpregel.",
      recipientContains: "Ontvanger bevat",
      recipientContainsTooltip:
        "Filtert op een deel van een van de geadresseerden. Handig als één postbus meerdere aliassen ontvangt.",
    },
    en: {
      title: "IMAP mail",
      description: "Read an external mailbox over IMAP or POP3 and start a case per incoming e-mail.",
      configurationTitle: "Configuration name",

      protocol: "Protocol",
      protocolTooltip:
        "IMAP supports folders and a seen flag; POP3 can only read the inbox and delete messages.",
      host: "Server",
      port: "Port",
      portTooltip: "Leave empty to use the default port of the selected protocol.",
      username: "Username",
      usernameTooltip: "Usually the full e-mail address of the mailbox.",

      authentication: "Authentication",
      authenticationTooltip:
        "Microsoft 365 no longer accepts a password for IMAP or POP3, so use OAuth2 there. On-premise Exchange, Dovecot and Zimbra generally still accept a password.",
      password: "Password",
      oauthTokenUrl: "OAuth2 token endpoint",
      oauthTokenUrlTooltip:
        "The full token endpoint URL. For Microsoft 365: https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token",
      oauthClientId: "OAuth2 client id",
      oauthClientSecret: "OAuth2 client secret",
      oauthScope: "OAuth2 scope",
      oauthScopeTooltip:
        "Leave empty for Microsoft 365. The app registration needs the IMAP.AccessAsApp permission.",
      startTlsEnable: "Use STARTTLS",
      startTlsEnableTooltip:
        "Requires STARTTLS on the plaintext port. Without it, credentials would travel in the clear.",

      folder: "Folder",
      postProcessAction: "After processing",
      postProcessActionTooltip:
        "What happens to an e-mail once a process has been started for it. This is what stops the next poll from picking up the same message again.",
      targetFolder: "Target folder",
      maxMessagesPerPoll: "Maximum e-mails per poll",
      maxMessagesPerPollTooltip:
        "Caps how many e-mails are handled at a time. The rest follow on the next poll.",
      debug: "Debug logging",
      debugTooltip:
        "Writes the entire protocol dialogue, including senders, headers and message content. Only enable this to investigate a problem.",

      "receive-mail": "Receive mail",
      actionDescription:
        "Starts or continues this process for each e-mail read from the mailbox. Leave the fields empty to handle all mail, or fill in a filter to let this process pick up only part of it.",
      senderContains: "Sender contains",
      senderContainsTooltip: "Filters on part of the sender's e-mail address.",
      subjectContains: "Subject contains",
      subjectContainsTooltip: "Filters on part of the subject line.",
      recipientContains: "Recipient contains",
      recipientContainsTooltip:
        "Filters on part of any recipient. Useful when one mailbox receives several aliases.",
    },
  },
};

export {imapMailPluginSpecification};
