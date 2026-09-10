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

package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

internal enum class FfmAbiFunction(
    val symbol: String,
    val descriptor: FunctionDescriptor,
) {
    ABI_VERSION("compukter_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
    MAXIMUM_OUTCOME_BYTES("compukter_max_outcome_bytes", FunctionDescriptor.of(ValueLayout.JAVA_LONG)),
    MAXIMUM_CREATE_BYTES("compukter_max_create_bytes", FunctionDescriptor.of(ValueLayout.JAVA_LONG)),
    VERIFY_ARTIFACT("compukter_verify_artifact", status(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
    STORE_OPEN(
        "compukter_store_open",
        status(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    STORE_HEALTH(
        "compukter_store_health",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    ),
    STORE_DURABLE_GENERATION(
        "compukter_store_durable_generation",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    STORE_FLUSH(
        "compukter_store_flush",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    ),
    STORE_TOMBSTONE("compukter_store_tombstone", status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)),
    STORE_RECOVER("compukter_store_recover", status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)),
    STORE_CLOSE("compukter_store_close", status(ValueLayout.JAVA_LONG)),
    CREATE(
        "compukter_create",
        status(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    CREATE_IN_STORE(
        "compukter_create_in_store",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    CREATE_BOOT_IN_STORE(
        "compukter_create_boot_in_store",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    CLOSE("compukter_close", status(ValueLayout.JAVA_LONG)),
    REDSTONE_SUBMIT_INPUT(
        "compukter_redstone_submit_input",
        status(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT),
    ),
    REDSTONE_CONFIRM_OUTPUT(
        "compukter_redstone_confirm_output",
        status(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT),
    ),
    VERIFY_FOR_DEPLOY(
        "compukter_verify_for_deploy",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    ),
    DEPLOYMENT_CANDIDATE_CLOSE("compukter_deployment_candidate_close", status(ValueLayout.JAVA_LONG)),
    EXECUTABLE_REVISION(
        "compukter_executable_revision",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    DEPLOY(
        "compukter_deploy",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    SUBMIT_CANONICAL_LINE(
        "compukter_submit_canonical_line",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    ),
    FILESYSTEM_GENERATION(
        "compukter_filesystem_generation",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    ),
    RESOURCE_SNAPSHOT(
        "compukter_resource_snapshot",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    ),
    FILESYSTEM_STAT(
        "compukter_filesystem_stat",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    FILESYSTEM_LIST(
        "compukter_filesystem_list",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    FILESYSTEM_READ(
        "compukter_filesystem_read",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    ADVANCE(
        "compukter_advance",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    COMPILATION_REQUEST_SIZE(
        "compukter_compilation_request_size",
        status(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    ),
    COMPILATION_REQUEST_COPY(
        "compukter_compilation_request_copy",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    COMPILATION_COMPLETE(
        "compukter_compilation_complete",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    ),
    RESUME_UNIT(
        "compukter_resume_unit",
        status(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    ),
    RESUME_STRING(
        "compukter_resume_string",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    ),
    RESUME_FAILURE(
        "compukter_resume_failure",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    ),
    TERMINAL_COMMIT("compukter_terminal_commit", status(ValueLayout.JAVA_LONG)),
    TERMINAL_FULL_STATE(
        "compukter_terminal_full_state",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    ),
    TERMINAL_CHANGES_SINCE(
        "compukter_terminal_changes_since",
        status(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    ),
    TERMINAL_KEY(
        "compukter_terminal_key",
        status(ValueLayout.JAVA_LONG, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    ),
    TERMINAL_TEXT(
        "compukter_terminal_text",
        status(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    ),
}

private fun status(vararg parameters: MemoryLayout): FunctionDescriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, *parameters)
