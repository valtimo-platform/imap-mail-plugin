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

package com.ritense.plugin.sandbox

import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.net.InetAddress

/*
 * @EnableSchedulerLock is what makes the plugin's @SchedulerLock annotations do anything:
 * without it ShedLock installs no interceptor and every node polls the mailbox on its own
 * schedule. Valtimo core supplies the LockProvider and the `shedlock` table, so this is the
 * only wiring an application has to add - the same line the GZAC application carries.
 */
@SpringBootApplication
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
class PluginApplication {
    companion object {
        private val logger = KotlinLogging.logger {}

        @JvmStatic
        fun main(args: Array<String>) {
            val app = runApplication<PluginApplication>(*args)

            logger.info {
                """

                ----------------------------------------------------------
                |    Application '${app.environment.getProperty("spring.application.name")}' is running!
                |    Active profile(s): [${app.environment.getProperty("spring.profiles.active")}].
                |    Local URL: [http://127.0.0.1:${app.environment.getProperty("server.port")}].
                |    External URL: [http://${InetAddress.getLocalHost().hostAddress}:${app.environment.getProperty(
                    "server.port",
                )}]
                ----------------------------------------------------------
                """.trimIndent()
            }
        }
    }
}
