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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.preferences.IdePreferences
import ru.lazyhat.compukters.ide.client.preferences.IdeProjectEditorState
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeClientPreferencesTest {
    @Test
    fun `session state round trips separately from persistent layout`() {
        val root = createTempDirectory("compukters-ide-preferences-").toAbsolutePath().normalize()
        try {
            val layout = RecordingIdeLayoutStore(IdeLayoutSettings.admit(180, 120, true))
            val preferences = IdeClientPreferences(root.resolve("session.preferences"), layout)
            preferences.save(IdePreferences.admit("проект", "src/главная.kt", 12, 4, 5, 999, 888, false))

            assertEquals(0, layout.saves.size, "ordinary session saves must not rewrite NeoForge layout config")
            val restored = preferences.load()!!
            assertEquals("проект", restored.lastProjectDirectory)
            assertEquals("src/главная.kt", restored.lastFile?.value)
            assertEquals(12, restored.caretUtf16)
            assertEquals(4, restored.firstVisibleLine)
            assertEquals(5, restored.firstVisibleColumn)
            assertEquals(180, restored.treeWidth)
            assertEquals(120, restored.diagnosticsHeight)
            assertEquals(true, restored.diagnosticsExpanded)

            preferences.saveLayout(IdeLayoutSettings.admit(233, 151, false))
            assertEquals(listOf(IdeLayoutSettings.admit(233, 151, false)), layout.saves)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple project states round trip and format one migrates`() {
        val root = createTempDirectory("compukters-ide-preferences-projects-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("session.preferences")
            val preferences = IdeClientPreferences(file, RecordingIdeLayoutStore(IdeLayoutSettings.defaults()))
            preferences.save(
                IdePreferences.admit(
                    "second",
                    linkedMapOf(
                        "first" to IdeProjectEditorState.admit("src/first.kt", 11, 2, 3),
                        "second" to IdeProjectEditorState.admit("src/second.kt", 22, 4, 5),
                    ),
                    240,
                    160,
                    true,
                ),
            )

            val restored = preferences.load()!!
            assertEquals("second", restored.lastProjectDirectory)
            assertEquals("src/first.kt", restored.projectState("first")?.file?.value)
            assertEquals(11, restored.projectState("first")?.caretUtf16)
            assertEquals("src/second.kt", restored.projectState("second")?.file?.value)

            val encodedProject = Base64.getUrlEncoder().withoutPadding().encodeToString("legacy".encodeToByteArray())
            val encodedFile = Base64.getUrlEncoder().withoutPadding().encodeToString("src/main.kt".encodeToByteArray())
            file.writeText("format=1\nproject=$encodedProject\nfile=$encodedFile\ncaret=12\nline=4\ncolumn=5\n")

            val migrated = preferences.load()!!
            assertEquals("legacy", migrated.lastProjectDirectory)
            assertEquals("src/main.kt", migrated.projectState("legacy")?.file?.value)
            assertEquals(12, migrated.projectState("legacy")?.caretUtf16)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing malformed and oversized session files recover without state`() {
        val root = createTempDirectory("compukters-ide-preferences-invalid-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("session.preferences")
            val preferences = IdeClientPreferences(file, RecordingIdeLayoutStore(IdeLayoutSettings.defaults()))
            assertNull(preferences.load())

            file.writeText("format=wrong\n")
            assertNull(preferences.load())

            file.writeBytes(ByteArray(IdeClientPreferences.MAXIMUM_FILE_BYTES + 1))
            assertNull(preferences.load())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private class RecordingIdeLayoutStore(
    private var current: IdeLayoutSettings,
) : IdeLayoutStore {
    val saves = mutableListOf<IdeLayoutSettings>()

    override fun load(): IdeLayoutSettings = current

    override fun save(settings: IdeLayoutSettings) {
        current = settings
        saves += settings
    }
}
