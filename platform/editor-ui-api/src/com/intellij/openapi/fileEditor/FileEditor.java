// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.fileEditor;

import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.diagnostic.PluginException;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.*;

import javax.swing.*;
import java.beans.PropertyChangeListener;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 *
 * @see TextEditor
 */
public interface FileEditor extends UserDataHolder, Disposable {
  /** @see #isModified() */
  String PROP_MODIFIED = "modified";
  /** @see #isValid() */
  String PROP_VALID = "valid";

  FileEditor[] EMPTY_ARRAY = {};

  /**
   * @return component which represents editor in the UI.
   * The method should never return {@code null}.
   */
  @NotNull JComponent getComponent();

  /**
   * Returns component to be focused when editor is opened.
   */
  @Nullable JComponent getPreferredFocusedComponent();

  /**
   * @return editor's name, a string that identifies editor among
   * other editors. For example, UI form might have two editor: "GUI Designer"
   * and "Text". So "GUI Designer" can be a name of one editor and "Text"
   * can be a name of other editor. The method should never return {@code null}.
   */
  @Nls(capitalization = Nls.Capitalization.Title) @NotNull String getName();

  /**
   * @return editor's internal state. Method should never return {@code null}.
   */
  default @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
    return FileEditorState.INSTANCE;
  }

  /**
   * Applies given state to the editor.
   * @param state cannot be null
   */
  void setState(@NotNull FileEditorState state);

  /**
   * In some cases, it's desirable to set state exactly as requested (e.g. on tab splitting), in other cases a different behaviour is
   * preferred, e.g. bringing caret into view on text editor opening. This method passes additional flag to FileEditor to indicate
   * the desired way to set state.
   */
  default void setState(@NotNull FileEditorState state, boolean exactState) {
    setState(state);
  }
  /**
   * @return whether the editor's content is modified in comparison with its file.
   */
  boolean isModified();

  /**
   * @return whether the editor is valid or not. An editor is valid if the contents displayed in it still exists. For example, an editor
   * displaying the contents of a file stops being valid if the file is deleted. Editor can also become invalid when it's disposed.
   */
  boolean isValid();

  /**
   * This method is invoked each time when the editor is selected.
   * This can happen in two cases: editor is selected because the selected file
   * has been changed or editor for the selected file has been changed.
   */
  default void selectNotify() { }

  /**
   * This method is invoked each time when the editor is deselected.
   */
  default void deselectNotify() { }

  /**
   * Adds specified listener.
   *
   * @param listener to be added
   */
  void addPropertyChangeListener(@NotNull PropertyChangeListener listener);

  /**
   * Removes specified listener.
   *
   * @param listener to be removed
   */
  void removePropertyChangeListener(@NotNull PropertyChangeListener listener);

  /**
   * @return highlighter object to perform background analysis and highlighting activities.
   * Return {@code null} if no background highlighting activity necessary for this file editor.
   */
  default @Nullable BackgroundEditorHighlighter getBackgroundHighlighter() {
    return null;
  }

  /**
   * The method is optional. Currently is used only by find usages subsystem
   * @return the location of user focus. Typically it's a caret or any other form of selection start.
   */
  @Nullable FileEditorLocation getCurrentLocation();

  default @Nullable StructureViewBuilder getStructureViewBuilder() {
    return null;
  }

  @ApiStatus.Internal
  Key<VirtualFile> FILE_KEY = Key.create("FILE_KEY");

  /**
   * Returns the file for which {@link FileEditorProvider#createEditor)} was called.
   * The default implementation is temporary, and shall be dropped in future.
   */
  default @Nullable VirtualFile getFile() {
    PluginException.reportDeprecatedDefault(getClass(), "getFile", "A proper @NotNull implementation required");
    return FILE_KEY.get(this);
  }
}
