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

import {PluginConfigurationData} from "@valtimo/plugin";

/**
 * Keep these three sets in lockstep with their Kotlin enums (`MailProtocol`,
 * `AuthenticationMode`, `PostProcessAction`). The backend parses whatever string it is
 * given and rejects what it does not recognise, so a value that drifts here surfaces at
 * poll time rather than at save time.
 */
const MAIL_PROTOCOLS = {
  IMAPS: "imaps",
  IMAP: "imap",
  POP3S: "pop3s",
  POP3: "pop3",
} as const;

const AUTHENTICATION_MODES = {
  BASIC: "BASIC",
  XOAUTH2: "XOAUTH2",
} as const;

const POST_PROCESS_ACTIONS = {
  MARK_READ: "MARK_READ",
  MOVE: "MOVE",
  DELETE: "DELETE",
  NONE: "NONE",
} as const;

type MailProtocol = (typeof MAIL_PROTOCOLS)[keyof typeof MAIL_PROTOCOLS];
type AuthenticationMode = (typeof AUTHENTICATION_MODES)[keyof typeof AUTHENTICATION_MODES];
type PostProcessAction = (typeof POST_PROCESS_ACTIONS)[keyof typeof POST_PROCESS_ACTIONS];

/** Mirrors `MailProtocol.defaultPort`, so the form can follow the protocol dropdown. */
const DEFAULT_PORTS: Record<MailProtocol, number> = {
  [MAIL_PROTOCOLS.IMAPS]: 993,
  [MAIL_PROTOCOLS.IMAP]: 143,
  [MAIL_PROTOCOLS.POP3S]: 995,
  [MAIL_PROTOCOLS.POP3]: 110,
};

/** POP3 exposes one folder and no flags; mirrors `MailProtocol.supportsFolders`. */
const POP3_PROTOCOLS: readonly MailProtocol[] = [MAIL_PROTOCOLS.POP3S, MAIL_PROTOCOLS.POP3];

interface ImapMailPluginConfig extends PluginConfigurationData {
  host: string;
  protocol?: MailProtocol;
  port?: number;
  username: string;
  password?: string;
  authentication?: AuthenticationMode;
  oauthTokenUrl?: string;
  oauthClientId?: string;
  oauthClientSecret?: string;
  oauthScope?: string;
  folder?: string;
  startTlsEnable?: boolean;
  maxMessagesPerPoll?: number;
  postProcessAction?: PostProcessAction;
  targetFolder?: string;
  debug?: boolean;
}

/** Action properties of the `receive-mail` process link: all optional, all AND-ed. */
interface ReceiveMailConfig {
  senderContains?: string;
  subjectContains?: string;
  recipientContains?: string;
}

export {
  AUTHENTICATION_MODES,
  AuthenticationMode,
  DEFAULT_PORTS,
  ImapMailPluginConfig,
  MAIL_PROTOCOLS,
  MailProtocol,
  POP3_PROTOCOLS,
  POST_PROCESS_ACTIONS,
  PostProcessAction,
  ReceiveMailConfig,
};
