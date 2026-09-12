/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.platform.k2

import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirEmptySymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirDefaultImportsProviderHolder
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.scopes.impl.FirEnumEntriesSupport
import org.jetbrains.kotlin.fir.session.AbstractFirMetadataSessionFactory
import org.jetbrains.kotlin.fir.session.FirSessionConfigurator
import org.jetbrains.kotlin.resolve.DefaultImportsProvider

/** K2 session factory whose target identity cannot route through a foreign platform factory. */
class CompuktersFirSessionFactory : AbstractFirMetadataSessionFactory(CompuktersPlatforms.default) {
    override val createSeparateSharedProvidersInHmppCompilation: Boolean = false

    override fun createPlatformSpecificSharedProviders(
        session: FirSession,
        moduleData: FirModuleData,
        scopeProvider: FirKotlinScopeProvider,
        context: Context,
    ): List<FirSymbolProvider> = listOf(FirEmptySymbolProvider(session))

    override fun FirSessionConfigurator.registerPlatformCheckers() = Unit

    override fun FirSessionConfigurator.registerExtraPlatformCheckers() = Unit

    @OptIn(SessionConfiguration::class)
    override fun FirSession.registerSourceSessionComponents(c: Context) {
        register(FirEnumEntriesSupport(this))
        register(FirDefaultImportsProviderHolder.Single(CompuktersDefaultImportsProvider))
    }
}

private object CompuktersDefaultImportsProvider : DefaultImportsProvider() {
    override val platformSpecificDefaultImports = emptyList<org.jetbrains.kotlin.resolve.ImportPath>()
}
