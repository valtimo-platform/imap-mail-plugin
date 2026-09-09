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

package com.ritense.valtimoplugins.imapmail.plugin

import com.ritense.plugin.PluginFactory
import com.ritense.plugin.service.PluginService

/**
 * Creates a plugin instance per configuration. The framework injects the `@PluginProperty`
 * fields afterwards, which is why nothing is passed in here: the plugin holds only
 * configuration, and the collaborators that do the work are wired into the poller instead.
 */
open class ImapMailPluginFactory(
    pluginService: PluginService,
) : PluginFactory<ImapMailPlugin>(pluginService) {
    override fun create(): ImapMailPlugin = ImapMailPlugin()
}
