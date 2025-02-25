// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.ui

import com.intellij.openapi.application.runInEdt
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.paint.RectanglePainter
import com.intellij.util.ui.TimerUtil
import java.awt.*
import java.util.*
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

private const val pulsationSize = 20

object LearningUiHighlightingManager {
  data class HighlightingOptions(
    val highlightBorder: Boolean = true,
    val highlightInside: Boolean = true,
    val usePulsation: Boolean = false,
    val clearPreviousHighlights: Boolean = true,
  )

  private val highlights: MutableList<RepaintHighlighting<*>> = ArrayList()

  val highlightingComponents: List<Component> get() = highlights.map { it.original }

  fun highlightComponent(original: Component, options: HighlightingOptions = HighlightingOptions()) {
    highlightPartOfComponent(original, options) { Rectangle(Point(0, 0), it.size) }
  }

  fun highlightJListItem(list: JList<*>,
                         options: HighlightingOptions = HighlightingOptions(),
                         index: () -> Int?) {
    highlightPartOfComponent(list, options) l@{
      val i = index() ?: return@l null
      val itemRect = list.getCellBounds(i, i)
      val listRect = list.visibleRect
      // return null if item rect is not intersecting with list visible rect
      if (itemRect.y >= listRect.y + listRect.height || itemRect.y + itemRect.height <= listRect.y) return@l null
      val adjustedY = max(itemRect.y, listRect.y)
      val adjustedHeight = min(itemRect.height, min(itemRect.y + itemRect.height - listRect.y, listRect.y + listRect.height - itemRect.y))
      Rectangle(itemRect.x, adjustedY, itemRect.width, adjustedHeight)
    }
  }

  fun highlightJTreeItem(tree: JTree,
                         options: HighlightingOptions = HighlightingOptions(),
                         path: () -> TreePath?) {
    highlightPartOfComponent(tree, options) {
      path()?.let {
        val treeRect = tree.visibleRect
        val pathRect = tree.getPathBounds(it) ?: return@let null
        val offset = pathRect.x - treeRect.x
        val width = min(treeRect.width - offset, pathRect.width)
        Rectangle(pathRect.x, pathRect.y, width, pathRect.height)
      }
    }
  }

  fun <T : Component> highlightPartOfComponent(component: T,
                                               options: HighlightingOptions = HighlightingOptions(),
                                               rectangle: (T) -> Rectangle?) {
    highlightComponent(component, options.clearPreviousHighlights) { glassPane ->
      RepaintHighlighting(component, glassPane, options) { rectangle(component) }
    }
  }

  fun clearHighlights() {
    runInEdt {
      for (core in highlights) {
        removeIt(core)
      }
      highlights.clear()
    }
  }

  private fun highlightComponent(original: Component,
                                 clearPreviousHighlights: Boolean,
                                 init: (glassPane: JComponent) -> RepaintHighlighting<*>) {
    runInEdt {
      if (clearPreviousHighlights) clearHighlights()
      if (!original.isShowing) return@runInEdt  // this check is required in rare cases when highlighting called after restore
      val glassPane = getGlassPane(original) ?: return@runInEdt
      val repaintByTimer = init(glassPane)
      repaintByTimer.reinitHighlightComponent()
      repaintByTimer.initTimer()
      highlights.add(repaintByTimer)
    }
  }

  internal fun removeIt(core: RepaintHighlighting<*>) {
    core.removed = true
    core.cleanup()
  }

  fun getRectangle(original: Component): Rectangle? =
    highlights.find { it.original == original }?.rectangle?.invoke()
}

internal class RepaintHighlighting<T : Component>(val original: T,
                                                  private val glassPane: JComponent,
                                                  val options: LearningUiHighlightingManager.HighlightingOptions,
                                                  val rectangle: () -> Rectangle?
) {
  var removed = false

  private val startDate = Date()
  private var listLocationOnScreen: Point? = null
  private var cellBoundsInList: Rectangle? = null
  private var highlightComponent: GlassHighlightComponent? = null
  private val pulsationOffset = if (options.usePulsation) pulsationSize else 0

  fun initTimer() {
    val timer = TimerUtil.createNamedTimer("IFT item", 50)
    timer.addActionListener {
      if (!original.isShowing) {
        LearningUiHighlightingManager.removeIt(this)
      }
      if (this.removed) {
        timer.stop()
        return@addActionListener
      }
      if (shouldReinit()) {
        cleanup()
        highlightComponent = null
        reinitHighlightComponent()
      }
      glassPane.repaint()
    }
    timer.start()
  }

  fun cleanup() {
    highlightComponent?.let { glassPane.remove(it) }
    if (glassPane.isValid) {
      glassPane.revalidate()
      glassPane.repaint()
    }
  }

  private fun shouldReinit(): Boolean {
    return highlightComponent == null || original.locationOnScreen != listLocationOnScreen || rectangle() != cellBoundsInList
  }

  fun reinitHighlightComponent() {
    val cellBounds = rectangle() ?: return

    val newHighlightComponent = GlassHighlightComponent(startDate, options)

    val pt = SwingUtilities.convertPoint(original, cellBounds.location, glassPane)
    val bounds = Rectangle(pt.x - pulsationOffset, pt.y - pulsationOffset, cellBounds.width + 2 * pulsationOffset,
                           cellBounds.height + 2 * pulsationOffset)

    newHighlightComponent.bounds = bounds
    glassPane.add(newHighlightComponent)
    highlightComponent = newHighlightComponent
    listLocationOnScreen = original.locationOnScreen
    cellBoundsInList = cellBounds
  }
}

internal class GlassHighlightComponent(private val startDate: Date,
                                       private val options: LearningUiHighlightingManager.HighlightingOptions) : JComponent() {

  private val pulsationOffset = if (options.usePulsation) pulsationSize else 0
  private var previous: Long = 0

  override fun paintComponent(g: Graphics) {
    val g2d = g as Graphics2D
    val r: Rectangle = bounds
    val oldColor = g2d.color
    val time = Date().time
    val delta = time - startDate.time
    previous = time
    val shift = if (pulsationOffset != 0 && (delta / 1000) % 4 == 2.toLong()) {
      (((delta / 25 + 20) % 40 - 20).absoluteValue).toInt()
    }
    else 0

    fun cyclicNumber(amplitude: Int, change: Long) = (change % (2 * amplitude) - amplitude).absoluteValue.toInt()
    val alphaCycle = cyclicNumber(1000, delta).toDouble() / 1000
    val magenta = ColorUtil.withAlpha(Color.magenta, 0.8)
    val orange = ColorUtil.withAlpha(Color.orange, 0.8)
    val background = ColorUtil.withAlpha(JBColor(Color(0, 0, shift * 10), Color(255 - shift * 10, 255 - shift * 10, 255)),
                                         (0.3 + 0.7 * shift / 20.0) * alphaCycle)
    val gradientShift = (delta / 20).toFloat()
    val gp = GradientPaint(gradientShift + 0F, gradientShift + 0F, magenta,
                           gradientShift + r.height.toFloat(), gradientShift + r.height.toFloat(), orange, true)

    val x = pulsationOffset - shift
    val y = pulsationOffset - shift
    val width = r.width - (pulsationOffset - shift) * 2
    val height = r.height - (pulsationOffset - shift) * 2
    RectanglePainter.paint(g2d, x, y, width, height, 2,
                           if (options.highlightInside) background else null,
                           if (options.highlightBorder) gp else null)
    g2d.color = oldColor
  }
}

private fun getGlassPane(component: Component): JComponent? {
  val rootPane = SwingUtilities.getRootPane(component)
  return if (rootPane == null) null else rootPane.glassPane as JComponent
}
