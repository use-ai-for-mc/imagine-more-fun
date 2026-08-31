package com.chenweikeng.imf.nra.wizard;

import com.chenweikeng.imf.nra.wizard.layout.ColumnBlock;
import com.chenweikeng.imf.nra.wizard.layout.RenderBlock;
import com.chenweikeng.imf.nra.wizard.layout.RowBlock;
import com.chenweikeng.imf.nra.wizard.layout.TextBlock;
import com.chenweikeng.imf.nra.wizard.layout.VerticalAlignment;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

public class WizardScreen extends Screen {
  private static final int HEADER_HEIGHT = 40;
  private static final int FOOTER_HEIGHT = 40;
  private static final int PADDING = 20;
  private static final int BUTTON_WIDTH = 80;
  private static final int BUTTON_HEIGHT = 20;

  private static final int TITLE_COLOR = 0xFFFFFFFF;
  private static final int HEADER_BG_COLOR = 0xDD000000;
  private static final int FOOTER_BG_COLOR = 0xDD000000;
  private static final String ACTION_PREFIX = "wizard_action:";

  private int currentPageIndex;
  private WizardPage currentPage;

  private Button backButton;
  private Button nextButton;
  private Button closeButton;

  private int scrollOffset;
  private int maxScrollOffset;

  public WizardScreen() {
    this(0);
  }

  public WizardScreen(int pageIndex) {
    super(Component.literal("Tutorial"));
    this.currentPageIndex = Math.max(0, pageIndex);
    this.currentPage = TutorialPages.getPage(this.currentPageIndex);
    this.scrollOffset = 0;
  }

  @Override
  protected void init() {
    super.init();

    if (currentPage != null) {
      currentPage.onPageOpen(minecraft);
    }

    int footerY = height - FOOTER_HEIGHT + 10;

    backButton =
        Button.builder(Component.literal("< Back"), this::onBackClicked)
            .bounds(PADDING, footerY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build();

    nextButton =
        Button.builder(Component.literal("Next >"), this::onNextClicked)
            .bounds(width / 2 - BUTTON_WIDTH / 2, footerY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build();

    closeButton =
        Button.builder(Component.literal("Close"), this::onCloseClicked)
            .bounds(width - PADDING - BUTTON_WIDTH, footerY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build();

    updateButtonVisibility();
    updateScrollBounds();

    addRenderableWidget(backButton);
    addRenderableWidget(nextButton);
    addRenderableWidget(closeButton);
  }

  private void updateButtonVisibility() {
    boolean isFirstPage = currentPageIndex == 0;
    boolean isLastPage = currentPageIndex >= TutorialPages.getPageCount() - 1;

    backButton.visible = !isFirstPage;
    nextButton.visible = currentPage.readyToGoNext();
    closeButton.visible = !isLastPage;

    if (isLastPage) {
      nextButton.setMessage(Component.literal("Finish"));
    } else {
      nextButton.setMessage(Component.literal("Next >"));
    }
  }

  private void updateScrollBounds() {
    int totalHeight = calculateTotalHeight();
    int visibleHeight = height - HEADER_HEIGHT - FOOTER_HEIGHT - PADDING * 2;

    if (totalHeight > visibleHeight) {
      maxScrollOffset = totalHeight - visibleHeight;
    } else {
      maxScrollOffset = 0;
    }

    scrollOffset = Math.min(scrollOffset, maxScrollOffset);
  }

  private int calculateTotalHeight() {
    if (currentPage == null) {
      return 0;
    }
    int textWidth = width - PADDING * 2;
    int total = 0;
    for (RenderBlock block : currentPage.getBlocks(minecraft)) {
      total += block.getHeight(textWidth, minecraft);
    }
    return total;
  }

  private void onBackClicked(Button button) {
    if (currentPageIndex > 0) {
      navigateToPage(currentPageIndex - 1);
    }
  }

  private void onNextClicked(Button button) {
    if (currentPageIndex < TutorialPages.getPageCount() - 1) {
      TutorialManager.getInstance().goToPage(currentPageIndex + 1);
      navigateToPage(currentPageIndex + 1);
    } else {
      TutorialManager.getInstance().finishTutorial();
      onClose();
    }
  }

  private void onCloseClicked(Button button) {
    onClose();
  }

  private void navigateToPage(int pageIndex) {
    goToPage(pageIndex);
  }

  public void goToPage(int pageIndex) {
    if (pageIndex < 0 || pageIndex >= TutorialPages.getPageCount()) {
      return;
    }
    if (currentPage != null) {
      currentPage.onPageClose(minecraft);
    }
    this.currentPageIndex = pageIndex;
    this.currentPage = TutorialPages.getPage(pageIndex);
    this.scrollOffset = 0;
    if (currentPage != null) {
      currentPage.onPageOpen(minecraft);
    }
    updateButtonVisibility();
    updateScrollBounds();
  }

  public int getCurrentPageIndex() {
    return currentPageIndex;
  }

  @Override
  public boolean mouseScrolled(
      double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
    if (maxScrollOffset > 0) {
      scrollOffset =
          Math.max(
              0,
              Math.min(
                  scrollOffset - (int) (verticalAmount * RenderBlock.LINE_HEIGHT),
                  maxScrollOffset));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
    if (mouseButtonEvent.button() == 0 && currentPage != null) {
      int mouseX = (int) mouseButtonEvent.x();
      int mouseY = (int) mouseButtonEvent.y();

      Style clickedStyle = getStyleAtMouse(mouseX, mouseY);
      if (clickedStyle != null) {
        ClickEvent clickEvent = clickedStyle.getClickEvent();
        if (clickEvent instanceof ClickEvent.RunCommand runCommand) {
          String command = runCommand.command();
          if (command.startsWith(ACTION_PREFIX)) {
            String action = command.substring(ACTION_PREFIX.length());
            WizardActionHandler.handle(action, minecraft);
            return true;
          }
        }
      }
    }
    return super.mouseClicked(mouseButtonEvent, bl);
  }

  private Style getStyleAtMouse(int mouseX, int mouseY) {
    if (currentPage == null) {
      return null;
    }

    int contentY = HEADER_HEIGHT + PADDING;
    int contentBottom = height - FOOTER_HEIGHT - PADDING;

    if (mouseY < contentY || mouseY > contentBottom) {
      return null;
    }

    int textWidth = width - PADDING * 2;
    int offsetMouseY = mouseY - contentY + scrollOffset;

    int y = 0;
    for (RenderBlock block : currentPage.getBlocks(minecraft)) {
      int blockHeight = block.getHeight(textWidth, minecraft);

      if (offsetMouseY >= y && offsetMouseY < y + blockHeight) {
        Style style = getStyleInBlock(block, mouseX, offsetMouseY - y, textWidth);
        if (style != null) {
          return style;
        }
      }

      y += blockHeight;
    }

    return null;
  }

  private Style getStyleInBlock(RenderBlock block, int mouseX, int relativeY, int width) {
    if (block instanceof TextBlock textBlock) {
      return getStyleInTextBlock(textBlock, mouseX, relativeY, width);
    } else if (block instanceof RowBlock rowBlock) {
      return getStyleInRowBlock(rowBlock, mouseX, relativeY, width);
    } else if (block instanceof ColumnBlock columnBlock) {
      return getStyleInColumnBlock(columnBlock, mouseX, relativeY, width);
    }
    return null;
  }

  private Style getStyleInRowBlock(RowBlock rowBlock, int mouseX, int relativeY, int width) {
    int totalGapWidth = (rowBlock.columns().size() - 1) * RowBlock.COLUMN_GAP;
    int columnWidth = (width - totalGapWidth) / rowBlock.columns().size();
    int columnX = 0;

    // Calculate maxHeight to match RowBlock.render()
    int maxHeight = 0;
    for (RenderBlock column : rowBlock.columns()) {
      maxHeight = Math.max(maxHeight, column.getHeight(columnWidth, minecraft));
    }

    for (RenderBlock column : rowBlock.columns()) {
      if (mouseX >= columnX && mouseX < columnX + columnWidth) {
        int adjustedRelativeY = relativeY;

        // Apply vertical offset for CENTER alignment, matching RowBlock.render()
        if (rowBlock.verticalAlignment() == VerticalAlignment.CENTER) {
          int columnHeight = column.getHeight(columnWidth, minecraft);
          adjustedRelativeY = relativeY - (maxHeight - columnHeight) / 2;
        }

        return getStyleInBlock(column, mouseX - columnX, adjustedRelativeY, columnWidth);
      }
      columnX += columnWidth + RowBlock.COLUMN_GAP;
    }
    return null;
  }

  private Style getStyleInColumnBlock(
      ColumnBlock columnBlock, int mouseX, int relativeY, int width) {
    int y = 0;
    for (RenderBlock child : columnBlock.blocks()) {
      int childHeight = child.getHeight(width, minecraft);
      if (relativeY >= y && relativeY < y + childHeight) {
        return getStyleInBlock(child, mouseX, relativeY - y, width);
      }
      y += childHeight;
    }
    return null;
  }

  private Style getStyleInTextBlock(TextBlock textBlock, int mouseX, int relativeY, int width) {
    List<FormattedCharSequence> lines = font.split(textBlock.text(), width);

    ActiveTextCollector.ClickableStyleFinder finder =
        new ActiveTextCollector.ClickableStyleFinder(font, mouseX, relativeY);

    int y = 0;
    for (FormattedCharSequence line : lines) {
      finder.accept(PADDING, y, line);
      y += RenderBlock.LINE_HEIGHT;
    }

    return finder.result();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    if (currentPage != null && nextButton.visible == false && currentPage.readyToGoNext()) {
      Minecraft client = Minecraft.getInstance();
      client.execute(
          () -> {
            client.setScreenAndShow(new WizardScreen(currentPageIndex));
          });
    }

    renderDarkBackground(graphics);
    renderHeader(graphics);
    renderBlocks(graphics);
    renderFooter(graphics);

    super.extractRenderState(graphics, mouseX, mouseY, delta);
  }

  private void renderHeader(GuiGraphicsExtractor graphics) {
    graphics.fill(0, 0, width, HEADER_HEIGHT, HEADER_BG_COLOR);

    if (currentPage != null) {
      graphics.centeredText(
          font, currentPage.getTitle(), width / 2, (HEADER_HEIGHT - 8) / 2, TITLE_COLOR);
    }
  }

  private void renderBlocks(GuiGraphicsExtractor graphics) {
    if (currentPage == null) {
      return;
    }

    int contentY = HEADER_HEIGHT + PADDING;
    int contentHeight = height - HEADER_HEIGHT - FOOTER_HEIGHT - PADDING * 2;
    int textWidth = width - PADDING * 2;

    graphics.enableScissor(0, contentY, width, contentY + contentHeight);

    int y = contentY - scrollOffset;
    for (RenderBlock block : currentPage.getBlocks(minecraft)) {
      int blockHeight = block.getHeight(textWidth, minecraft);
      if (y + blockHeight > contentY && y < contentY + contentHeight) {
        block.render(graphics, PADDING, y, textWidth, minecraft);
      }
      y += blockHeight;
    }

    graphics.disableScissor();
  }

  private void renderFooter(GuiGraphicsExtractor graphics) {
    int footerY = height - FOOTER_HEIGHT;
    graphics.fill(0, footerY, width, height, FOOTER_BG_COLOR);
  }

  private void renderDarkBackground(GuiGraphicsExtractor graphics) {
    graphics.fill(0, 0, this.width, this.height, 0xCC000000);
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return true;
  }

  @Override
  public void onClose() {
    if (currentPage != null) {
      currentPage.onPageClose(minecraft);
    }
    super.onClose();
  }
}
