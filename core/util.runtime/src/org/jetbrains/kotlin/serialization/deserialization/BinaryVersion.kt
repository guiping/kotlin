/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.serialization.deserialization

/**
 * Subclasses of this class are used to identify different versions of the binary output of the compiler and their compatibility guarantees.
 * - Major version should be increased only when the new binary format is neither forward- nor backward compatible.
 *   This shouldn't really ever happen at all.
 * - Minor version should be increased when the new format is backward compatible,
 *   i.e. the new compiler can process old data, but the old compiler will not be able to process new data.
 * - Patch version can be increased freely and is only supposed to be used for debugging. Increase the patch version when you
 *   make a change to binaries which is both forward- and backward compatible.
 */
abstract class BinaryVersion protected constructor(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val rest: List<Int>
) {
    abstract fun isCompatible(): Boolean

    fun toArray(): IntArray =
            intArrayOf(major, minor, patch, *rest.toIntArray())

    /**
     * Returns true if this version of some format loaded from some binaries is compatible
     * to the expected version of that format in the current compiler.
     *
     * @param ourVersion the version of this format in the current compiler
     */
    protected fun isCompatibleTo(ourVersion: BinaryVersion): Boolean {
        return if (major == 0) ourVersion.major == 0 && minor == ourVersion.minor
        else major == ourVersion.major && minor <= ourVersion.minor
    }

    override fun toString(): String {
        val versions = toArray().takeWhile { it != UNKNOWN }
        return if (versions.isEmpty()) "unknown" else versions.joinToString(".")
    }

    override fun equals(other: Any?) =
            this.javaClass == other?.javaClass &&
            major == (other as BinaryVersion).major && minor == other.minor && patch == other.patch && rest == other.rest

    override fun hashCode(): Int{
        var result = major
        result += 31 * result + minor
        result += 31 * result + patch
        result += 31 * result + rest.hashCode()
        return result
    }

    companion object {
        private val UNKNOWN = -1

        @JvmStatic
        fun <T : BinaryVersion> create(
                version: IntArray,
                factory: (major: Int, minor: Int, patch: Int, rest: List<Int>) -> T
        ): T {
            return factory(
                    version.getOrNull(0) ?: UNKNOWN,
                    version.getOrNull(1) ?: UNKNOWN,
                    version.getOrNull(2) ?: UNKNOWN,
                    if (version.size > 3) version.asList().subList(3, version.size).toList() else emptyList()
            )
        }

        @JvmStatic
        fun <T : BinaryVersion> create(
                major: Int, minor: Int, patch: Int, factory: (major: Int, minor: Int, patch: Int, rest: List<Int>) -> T
        ): T = factory(major, minor, patch, emptyList())
    }
}
