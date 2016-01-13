/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.diagnostics.rendering;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.diagnostics.DiagnosticWithParameters3;
import org.jetbrains.kotlin.diagnostics.DiagnosticWithParameters4;
import org.jetbrains.kotlin.renderer.Renderer;

public class DiagnosticWithParameters4Renderer<A, B, C, D> extends AbstractDiagnosticWithParametersRenderer<DiagnosticWithParameters4<?, A, B, C, D>> {
    private final Renderer<? super A> rendererForA;
    private final Renderer<? super B> rendererForB;
    private final Renderer<? super C> rendererForC;
    private final Renderer<? super D> rendererForD;

    public DiagnosticWithParameters4Renderer(@NotNull String message,
            @Nullable Renderer<? super A> rendererForA,
            @Nullable Renderer<? super B> rendererForB,
            @Nullable Renderer<? super C> rendererForC,
            @Nullable Renderer<? super D> rendererForD) {
        super(message);
        this.rendererForA = rendererForA;
        this.rendererForB = rendererForB;
        this.rendererForC = rendererForC;
        this.rendererForD = rendererForD;
    }

    @NotNull
    @Override
    public Object[] renderParameters(@NotNull DiagnosticWithParameters4<?, A, B, C, D> diagnostic) {
        return new Object[]{
                DiagnosticRendererUtilKt.renderParameter(diagnostic.getA(), rendererForA),
                DiagnosticRendererUtilKt.renderParameter(diagnostic.getB(), rendererForB),
                DiagnosticRendererUtilKt.renderParameter(diagnostic.getC(), rendererForC),
                DiagnosticRendererUtilKt.renderParameter(diagnostic.getD(), rendererForD)};
    }
}
