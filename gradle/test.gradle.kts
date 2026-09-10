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

tasks.named<Test>("test") {
    systemProperty("spring.profiles.include", "inttest,postgresql")
    useJUnitPlatform()

    // No teardown hook here: `dockerCompose { isRequiredBy(tasks.test) }` in
    // backend/plugin/build.gradle.kts already makes composeUp run before the tests and
    // composeDown after them. This block used to hold `doLast { "composeDownForced" }`,
    // which evaluated a string and discarded it - it looked like teardown but did nothing,
    // and the real teardown was never missing.
}
