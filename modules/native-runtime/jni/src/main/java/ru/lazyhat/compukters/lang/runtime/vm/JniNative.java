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

package ru.lazyhat.compukters.lang.runtime.vm;

final class JniNative {
    private JniNative() {}

    static native int abiVersion();

    static native long maximumOutcomeBytes();

    static native long maximumCreateBytes();

    static native int verifyArtifact(byte[] artifact);

    static native int storeOpen(byte[] root, byte[] limits, byte[] output, long[] written);

    static native int storeHealth(long handle, byte[] output, long[] written);

    static native int storeDurableGeneration(long handle, byte[] id, byte[] output, long[] written);

    static native int storeFlush(long handle, byte[] id, long generation);

    static native int storeTombstone(long handle, byte[] id);

    static native int storeRecover(long handle, byte[] id);

    static native int storeClose(long handle);

    static native int create(byte[] artifact, byte[] output, long[] written);

    static native int createInStore(
            long storeHandle,
            byte[] id,
            byte[] rom,
            byte[] artifact,
            byte[] output,
            long[] written);

    static native int createBootInStore(
            long storeHandle, byte[] id, byte[] rom, byte[] output, long[] written);

    static native int close(long handle);

    static native int submitRedstoneInput(long handle, int packet);

    static native int confirmRedstoneOutput(long handle, int packet);

    static native int verifyForDeploy(long handle, byte[] artifact, long[] candidateOut);

    static native int deploymentCandidateClose(long handle);

    static native int executableRevision(long handle, byte[] path, byte[] output, long[] written);

    static native int deploy(
            long handle,
            long candidateHandle,
            byte[] path,
            int expectedKind,
            long expectedGeneration,
            byte[] output,
            long[] written);

    static native int submitCanonicalLine(long handle, char[] line);

    static native int filesystemGeneration(long handle, byte[] output, long[] written);

    static native int resourceSnapshot(long handle, byte[] output, long[] written);

    static native int filesystemStat(long handle, byte[] path, byte[] output, long[] written);

    static native int filesystemList(
            long handle,
            byte[] path,
            byte[] startAfter,
            int maximumEntries,
            byte[] output,
            long[] written);

    static native int filesystemRead(
            long handle,
            byte[] path,
            long offset,
            int maximumBytes,
            long expectedGeneration,
            byte[] output,
            long[] written);

    static native int advance(
            long handle,
            int guestBudget,
            int maintenanceBudget,
            int hostRequestBudget,
            byte[] output,
            long[] written);

    static native int compilationRequestSize(long handle, long token, long[] requiredOut);

    static native int compilationRequestCopy(long handle, long token, byte[] output, long[] written);

    static native int compilationComplete(long handle, long token, int kind, byte[] payload);

    static native int resumeUnit(long handle, int taskId, long requestId);

    static native int resumeBool(long handle, int taskId, long requestId, boolean value);

    static native int resumeString(long handle, int taskId, long requestId, char[] value);

    static native int resumeFailure(long handle, int taskId, long requestId, int kind, int code);

    static native int terminalCommit(long handle);

    static native int terminalFullState(long handle, byte[] output, long[] written);

    static native int terminalChangesSince(
            long handle, long revision, byte[] output, long[] written);

    static native int terminalKey(long handle, int key, int action, int modifiers);

    static native int terminalText(long handle, int[] codePoints);
}
