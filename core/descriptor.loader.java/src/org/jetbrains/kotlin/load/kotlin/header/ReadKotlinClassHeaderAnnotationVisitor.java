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

package org.jetbrains.kotlin.load.kotlin.header;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.load.java.JvmBytecodeBinaryVersion;
import org.jetbrains.kotlin.load.kotlin.JvmMetadataVersion;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.name.Name;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jetbrains.kotlin.load.java.JvmAnnotationNames.*;
import static org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass.*;
import static org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader.Kind.*;

public class ReadKotlinClassHeaderAnnotationVisitor implements AnnotationVisitor {
    private static final boolean IGNORE_OLD_METADATA = "true".equals(System.getProperty("kotlin.ignore.old.metadata"));

    private static final Map<ClassId, KotlinClassHeader.Kind> HEADER_KINDS = new HashMap<ClassId, KotlinClassHeader.Kind>();

    static {
        HEADER_KINDS.put(ClassId.topLevel(KOTLIN_CLASS), CLASS);
        HEADER_KINDS.put(ClassId.topLevel(KOTLIN_FILE_FACADE), FILE_FACADE);
        HEADER_KINDS.put(ClassId.topLevel(KOTLIN_MULTIFILE_CLASS), MULTIFILE_CLASS);
        HEADER_KINDS.put(ClassId.topLevel(KOTLIN_MULTIFILE_CLASS_PART), MULTIFILE_CLASS_PART);
        HEADER_KINDS.put(ClassId.topLevel(KOTLIN_SYNTHETIC_CLASS), SYNTHETIC_CLASS);
    }

    private JvmMetadataVersion metadataVersion = null;
    private JvmBytecodeBinaryVersion bytecodeVersion = null;
    private String multifileClassName = null;
    private String[] annotationData = null;
    private String[] strings = null;
    private KotlinClassHeader.Kind headerKind = null;
    private KotlinDataSyntheticClassKind syntheticClassKind = null;
    private boolean isInterfaceDefaultImpls = false;
    private boolean isLocalClass = false;

    @Nullable
    public KotlinClassHeader createHeader() {
        if (headerKind == null) {
            return null;
        }

        if (metadataVersion == null || !metadataVersion.isCompatible()) {
            annotationData = null;
        }
        else if (shouldHaveData() && annotationData == null) {
            // This means that the annotation is found and its ABI version is compatible, but there's no "data" string array in it.
            // We tell the outside world that there's really no annotation at all
            return null;
        }

        return new KotlinClassHeader(
                headerKind,
                metadataVersion != null ? metadataVersion : JvmMetadataVersion.INVALID_VERSION,
                bytecodeVersion != null ? bytecodeVersion : JvmBytecodeBinaryVersion.INVALID_VERSION,
                annotationData,
                strings,
                multifileClassName,
                isInterfaceDefaultImpls || syntheticClassKind == KotlinDataSyntheticClassKind.INTERFACE_DEFAULT_IMPLS,
                isLocalClass || syntheticClassKind == KotlinDataSyntheticClassKind.LOCAL_CLASS
        );
    }

    private boolean shouldHaveData() {
        return headerKind == CLASS ||
               headerKind == FILE_FACADE ||
               headerKind == MULTIFILE_CLASS_PART;
    }

    @Nullable
    @Override
    public AnnotationArgumentVisitor visitAnnotation(@NotNull ClassId classId, @NotNull SourceElement source) {
        if (!IGNORE_OLD_METADATA) {
            FqName fqName = classId.asSingleFqName();
            if (KOTLIN_INTERFACE_DEFAULT_IMPLS.equals(fqName)) {
                isInterfaceDefaultImpls = true;
                return null;
            }
            else if (KOTLIN_LOCAL_CLASS.equals(fqName)) {
                isLocalClass = true;
                return null;
            }
        }

        if (classId.asSingleFqName().equals(METADATA)) {
            return new KotlinMetadataArgumentVisitor();
        }

        if (!IGNORE_OLD_METADATA) {
            if (headerKind != null) {
                // Ignore all Kotlin annotations except the first found
                return null;
            }

            KotlinClassHeader.Kind newKind = HEADER_KINDS.get(classId);
            if (newKind != null) {
                headerKind = newKind;
                return new HeaderAnnotationArgumentVisitor();
            }
        }

        return null;
    }

    @Override
    public void visitEnd() {
    }

    private class KotlinMetadataArgumentVisitor implements AnnotationArgumentVisitor {
        @Override
        public void visit(@Nullable Name name, @Nullable Object value) {
            if (name == null) return;

            String string = name.asString();
            if (KIND_FIELD_NAME.equals(string)) {
                if (value instanceof Integer) {
                    headerKind = KotlinClassHeader.Kind.getById((Integer) value);
                }
            }
            else if (METADATA_VERSION_FIELD_NAME.equals(string)) {
                if (value instanceof int[]) {
                    metadataVersion = JvmMetadataVersion.create((int[]) value);
                }
            }
            else if (BYTECODE_VERSION_FIELD_NAME.equals(string)) {
                if (value instanceof int[]) {
                    bytecodeVersion = JvmBytecodeBinaryVersion.create((int[]) value);
                }
            }
            else if (SYNTHETIC_CLASS_KIND_FIELD_NAME.equals(string)) {
                if (value instanceof Integer) {
                    syntheticClassKind = KotlinDataSyntheticClassKind.getById((Integer) value);
                }
            }
            else if (METADATA_MULTIFILE_CLASS_NAME_FIELD_NAME.equals(string)) {
                if (value instanceof String) {
                    multifileClassName = (String) value;
                }
            }
        }

        @Override
        @Nullable
        public AnnotationArrayArgumentVisitor visitArray(@NotNull Name name) {
            String string = name.asString();
            if (METADATA_DATA_FIELD_NAME.equals(string)) {
                return dataArrayVisitor();
            }
            else if (METADATA_STRINGS_FIELD_NAME.equals(string)) {
                return stringsArrayVisitor();
            }
            else {
                return null;
            }
        }

        @NotNull
        private AnnotationArrayArgumentVisitor dataArrayVisitor() {
            return new CollectStringArrayAnnotationVisitor() {
                @Override
                protected void visitEnd(@NotNull String[] data) {
                    annotationData = data;
                }
            };
        }

        @NotNull
        private AnnotationArrayArgumentVisitor stringsArrayVisitor() {
            return new CollectStringArrayAnnotationVisitor() {
                @Override
                protected void visitEnd(@NotNull String[] data) {
                    strings = data;
                }
            };
        }

        @Override
        public void visitEnum(@NotNull Name name, @NotNull ClassId enumClassId, @NotNull Name enumEntryName) {
        }

        @Nullable
        @Override
        public AnnotationArgumentVisitor visitAnnotation(@NotNull Name name, @NotNull ClassId classId) {
            return null;
        }

        @Override
        public void visitEnd() {
        }
    }

    private class HeaderAnnotationArgumentVisitor implements AnnotationArgumentVisitor {
        @Override
        public void visit(@Nullable Name name, @Nullable Object value) {
            if (name == null) return;

            String string = name.asString();
            if (VERSION_FIELD_NAME.equals(string)) {
                if (value instanceof int[]) {
                    metadataVersion = JvmMetadataVersion.create((int[]) value);

                    // If there's no bytecode binary version in the class file, we assume it to be equal to the metadata version
                    if (bytecodeVersion == null) {
                        bytecodeVersion = JvmBytecodeBinaryVersion.create((int[]) value);
                    }
                }
            }
            else if (MULTIFILE_CLASS_NAME_FIELD_NAME.equals(string)) {
                multifileClassName = value instanceof String ? (String) value : null;
            }
        }

        @Override
        @Nullable
        public AnnotationArrayArgumentVisitor visitArray(@NotNull Name name) {
            String string = name.asString();
            if (DATA_FIELD_NAME.equals(string) || FILE_PART_CLASS_NAMES_FIELD_NAME.equals(string)) {
                return dataArrayVisitor();
            }
            else if (STRINGS_FIELD_NAME.equals(string)) {
                return stringsArrayVisitor();
            }
            else {
                return null;
            }
        }

        @NotNull
        private AnnotationArrayArgumentVisitor dataArrayVisitor() {
            return new CollectStringArrayAnnotationVisitor() {
                @Override
                protected void visitEnd(@NotNull String[] data) {
                    annotationData = data;
                }
            };
        }

        @NotNull
        private AnnotationArrayArgumentVisitor stringsArrayVisitor() {
            return new CollectStringArrayAnnotationVisitor() {
                @Override
                protected void visitEnd(@NotNull String[] data) {
                    strings = data;
                }
            };
        }

        @Override
        public void visitEnum(@NotNull Name name, @NotNull ClassId enumClassId, @NotNull Name enumEntryName) {
        }

        @Nullable
        @Override
        public AnnotationArgumentVisitor visitAnnotation(@NotNull Name name, @NotNull ClassId classId) {
            return null;
        }

        @Override
        public void visitEnd() {
        }
    }

    private abstract static class CollectStringArrayAnnotationVisitor implements AnnotationArrayArgumentVisitor {
        private final List<String> strings;

        public CollectStringArrayAnnotationVisitor() {
            this.strings = new ArrayList<String>();
        }

        @Override
        public void visit(@Nullable Object value) {
            if (value instanceof String) {
                strings.add((String) value);
            }
        }

        @Override
        public void visitEnum(@NotNull ClassId enumClassId, @NotNull Name enumEntryName) {
        }

        @Override
        public void visitEnd() {
            //noinspection SSBasedInspection
            visitEnd(strings.toArray(new String[strings.size()]));
        }

        protected abstract void visitEnd(@NotNull String[] data);
    }
}
