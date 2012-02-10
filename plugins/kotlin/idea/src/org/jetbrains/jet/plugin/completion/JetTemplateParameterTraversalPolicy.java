package org.jetbrains.jet.plugin.completion;

import com.intellij.codeInsight.completion.TemplateParameterTraversalPolicy;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lexer.JetToken;
import org.jetbrains.jet.lexer.JetTokens;

/**
 * @author Evgeny Gerashchenko
 * @since 2/10/12
 */
public class JetTemplateParameterTraversalPolicy implements TemplateParameterTraversalPolicy {
    @Override
    public boolean isValidForFile(Editor editor, PsiFile file) {
        return file instanceof JetFile;
    }

    @Override
    public void invoke(Editor editor, PsiFile file, boolean next) {
        Project project = editor.getProject();
        if (project == null) {
            return;
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments();

        JetToken terminatingToken = next ? JetTokens.IDE_TEMPLATE_START : JetTokens.IDE_TEMPLATE_END;

        SelectionModel selModel = editor.getSelectionModel();
        PsiElement first = file.findElementAt((selModel.getSelectionStart() + selModel.getSelectionEnd()) / 2);
        PsiElement current = first;
        if (first != null) {
            do {
                if (current.getNode().getElementType() == terminatingToken) {
                    selectTemplate(editor, selModel, current, next);
                    return;
                }

                current = goToNextPrevElement(current, next);
            } while (current != first);
        }
    }

    @NotNull
    private static PsiElement goToNextPrevElement(@NotNull PsiElement element, boolean next) {
        if (next) {
            PsiElement nextLeaf = PsiTreeUtil.nextLeaf(element);
            if (nextLeaf == null) {
                PsiElement root = PsiTreeUtil.getTopmostParentOfType(element, JetFile.class);
                assert root != null;
                return PsiTreeUtil.firstChild(root);
            }
            return nextLeaf;
        } else {
            PsiElement prevLeaf = PsiTreeUtil.prevLeaf(element);
            if (prevLeaf == null) {
                PsiElement root = PsiTreeUtil.getTopmostParentOfType(element, JetFile.class);
                assert root != null;
                return PsiTreeUtil.lastChild(root);
            }
            return prevLeaf;
        }
    }

    private static void selectTemplate(Editor editor, SelectionModel selModel, PsiElement current, boolean next) {
        PsiElement match = goToNextPrevElement(goToNextPrevElement(current, next), next);
        JetToken expected = next ? JetTokens.IDE_TEMPLATE_END : JetTokens.IDE_TEMPLATE_START;
        if (expected != match.getNode().getElementType()) return;

        int start = Math.min(current.getTextOffset(), match.getTextOffset());
        int end = Math.max(current.getTextOffset() + current.getTextLength(),
                           match.getTextOffset() + match.getTextLength());

        editor.getCaretModel().moveToOffset(end);
        editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);

        editor.getCaretModel().moveToOffset(start);
        editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);

        selModel.setSelection(start, end);
    }
}
