// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.scratch.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import org.jetbrains.kotlin.idea.KotlinJvmBundle
import org.jetbrains.kotlin.idea.scratch.ScratchFile

class RunScratchFromHereAction : ScratchAction(
    KotlinJvmBundle.message("scratch.run.from.here.button"),
    AllIcons.Diff.ArrowRight
) {

    override fun actionPerformed(e: AnActionEvent) {
        val scratchFile = e.currentScratchFile ?: return

        doAction(scratchFile)
    }

    companion object {
        fun doAction(scratchFile: ScratchFile) {
            val executor = scratchFile.replScratchExecutor ?: return

            try {
                executor.executeNew()
            } catch (ex: Throwable) {
                executor.errorOccurs(KotlinJvmBundle.message("exception.occurs.during.run.scratch.action1"), ex, true)
            }
        }
    }
}