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

package ru.lazyhat.compukters.ide.project

import ru.lazyhat.compukters.ide.project.fs.ProjectRootIdentity
import ru.lazyhat.compukters.ide.project.fs.SecureProjectFiles
import java.nio.file.Path

class ProjectHandle internal constructor(
    val directoryName: String,
    val identity: ProjectRootIdentity,
) {
    val canonicalPath: Path get() = identity.canonicalPath

    fun isValid(): Boolean = SecureProjectFiles.isValid(identity)

    fun lockFileWriter(): LockFileWriter =
        object : LockFileWriter {
            override fun create(content: ByteArray) {
                SecureProjectFiles.writeNew(identity, LOCK_FILENAME, content.copyOf())
            }

            override fun update(content: ByteArray) {
                SecureProjectFiles.replace(identity, LOCK_FILENAME, content.copyOf())
            }
        }

    private companion object {
        const val LOCK_FILENAME = "compukter.lock"
    }
}

data class ProjectDescriptor(
    val directoryName: String,
    val manifest: ProjectManifest,
    val handle: ProjectHandle,
)
