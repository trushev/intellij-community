// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes.actions.diff

import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.combined.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ui.ChangesComparator
import com.intellij.openapi.vcs.changes.ui.PresentableChange

internal class CombinedChangeDiffVirtualFile(requestProducer: CombinedChangeDiffRequestProducer) :
  CombinedDiffVirtualFile<CombinedChangeDiffRequestProducer>(requestProducer) {

  override fun createProcessor(project: Project): DiffRequestProcessor = CombinedChangeDiffRequestProcessor(project, requestProducer)
}

internal class CombinedChangeDiffRequestProducer(private val project: Project?,
                                                 val producers: List<ChangeDiffRequestProducer>) : CombinedDiffRequestProducer {
  override fun getFilesSize(): Int = producers.size

  override fun getName(): String = VcsBundle.message("changes.combined.diff")

  override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
    val filePathComparator = ChangesComparator.getFilePathComparator(true)
    val requests = producers
      .asSequence()
      .mapNotNull { createChildRequest(project, it, context, indicator) }
      .sortedWith { r1, r2 -> filePathComparator.compare(r1.path, r2.path) }
      .toList()

    return CombinedDiffRequest(name, requests)
  }

  private fun createChildRequest(project: Project?,
                                 producer: ChangeDiffRequestProducer,
                                 context: UserDataHolder,
                                 indicator: ProgressIndicator): CombinedDiffRequest.ChildDiffRequest? {
    val change = producer.change
    val requestProducer = ChangeDiffRequestProducer.create(project, change) ?: return null
    val childRequest = requestProducer.process(context, indicator) as? ContentDiffRequest ?: return null

    return CombinedDiffRequest.ChildDiffRequest(childRequest, ChangesUtil.getFilePath(change), change.fileStatus)
  }
}

internal class CombinedChangeDiffRequestProcessor(project: Project?,
                                                  private val requestProducer: CombinedChangeDiffRequestProducer) :
  CombinedDiffRequestProcessor(project, requestProducer) {

  override fun createGoToChangeAction(): AnAction = MyGoToChangePopupProvider().createGoToChangeAction()

  private inner class MyGoToChangePopupProvider : SelectionAwareGoToChangePopupActionProvider() {
    private val presentableChanges: List<PresentableChange> = requestProducer.producers

    override fun getChanges(): List<PresentableChange> = presentableChanges

    override fun select(change: PresentableChange) {
      viewer?.selectDiffBlock(change.filePath, change.fileStatus, ScrollPolicy.DIFF_BLOCK)
    }

    override fun getSelectedChange(): PresentableChange? {
      val selected = viewer?.getCurrentBlockContent() ?: return null

      return presentableChanges.find { it.fileStatus == selected.fileStatus && it.filePath == selected.path }
    }
  }
}
