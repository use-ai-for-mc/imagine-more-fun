package com.chenweikeng.imf.nra.config.profile.ui;

import com.chenweikeng.imf.nra.config.ClothConfigScreen;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.profile.BuiltInProfiles;
import com.chenweikeng.imf.nra.config.profile.DataBundleExporter;
import com.chenweikeng.imf.nra.config.profile.ProfileManager;
import com.chenweikeng.imf.nra.config.profile.StoredProfile;
import com.chenweikeng.imf.nra.report.ui.RideReportListScreen;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class ProfileManagementScreen extends Screen {
  private static final int PADDING = 20;
  private static final int FOOTER_HEIGHT = 50;
  private static final int BUTTON_HEIGHT = 20;
  private static final int LABEL_COLOR = 0xFFFFFFFF;

  private record ProgressData(double percentage, long timeRemainingSeconds) {}

  private final Screen parent;
  private CurrentSettingsEntry currentSettingsEntry;
  private ProfileListWidget profileList;
  private Button exportButton;
  private Button importButton;
  private Button historyButton;
  private Button reportsButton;
  private Button resetButton;
  private Button closeButton;

  private String statusMessage = null;
  private boolean statusSuccess = true;
  private long statusMessageExpiry = 0;

  private StoredProfile pendingDeleteProfile = null;

  public ProfileManagementScreen(Screen parent) {
    super(Component.literal("Profiles"));
    this.parent = parent;
  }

  public ProfileManagementScreen() {
    this(null);
  }

  @Override
  protected void init() {
    super.init();

    int headerHeight = 60; // Increased to accommodate progress bars in same row
    int listGap = 15;
    int listY = PADDING + headerHeight + listGap;
    int listHeight = height - FOOTER_HEIGHT - PADDING - headerHeight - listGap;
    int currentSettingsY = PADDING + 30; // Move down to avoid overlap with progress bars

    int footerY = height - FOOTER_HEIGHT + 10;
    boolean showExportImport = DataBundleExporter.isFileDialogAvailable();
    int buttonCount = showExportImport ? 6 : 4;
    int buttonWidth = showExportImport ? 85 : 100;
    int buttonGap = showExportImport ? 6 : 10;

    int totalButtonWidth = buttonWidth * buttonCount + buttonGap * (buttonCount - 1);
    int startX = (width - totalButtonWidth) / 2;

    int col = 0;
    if (showExportImport) {
      exportButton =
          Button.builder(Component.literal("Export"), this::onExportClicked)
              .bounds(
                  startX + (buttonWidth + buttonGap) * col++, footerY, buttonWidth, BUTTON_HEIGHT)
              .build();
      addRenderableWidget(exportButton);

      importButton =
          Button.builder(Component.literal("Import"), this::onImportClicked)
              .bounds(
                  startX + (buttonWidth + buttonGap) * col++, footerY, buttonWidth, BUTTON_HEIGHT)
              .build();
      addRenderableWidget(importButton);
    }

    historyButton =
        Button.builder(Component.literal("History"), this::onHistoryClicked)
            .bounds(startX + (buttonWidth + buttonGap) * col++, footerY, buttonWidth, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(historyButton);

    reportsButton =
        Button.builder(
                Component.literal(showExportImport ? "Reports" : "Ride Reports"),
                this::onReportsClicked)
            .bounds(startX + (buttonWidth + buttonGap) * col++, footerY, buttonWidth, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(reportsButton);

    resetButton =
        Button.builder(Component.literal("Reset Builtins"), this::onResetClicked)
            .bounds(startX + (buttonWidth + buttonGap) * col++, footerY, buttonWidth, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(resetButton);

    closeButton =
        Button.builder(Component.literal("Close"), this::onCloseClicked)
            .bounds(startX + (buttonWidth + buttonGap) * col++, footerY, buttonWidth, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(closeButton);

    currentSettingsEntry =
        new CurrentSettingsEntry(
            minecraft,
            PADDING,
            currentSettingsY,
            width - PADDING * 2,
            this::openSettings,
            this::openSaveCurrent);

    profileList =
        new ProfileListWidget(
            minecraft,
            width,
            listHeight,
            listY,
            this::onProfileApply,
            this::onProfileRename,
            this::onProfileEdit,
            this::onProfileDeleteRequest,
            this::onSelectionChange);

    addRenderableWidget(profileList);
    profileList.setProfiles(ProfileManager.getAllProfiles());
  }

  private void updateButtonStates() {}

  private void onSelectionChange(StoredProfile profile) {
    updateButtonStates();
  }

  private void onProfileApply(StoredProfile profile) {
    if (profile == null) return;

    ProfileManager.activateProfile(profile.id);
    profileList.refreshProfiles();
  }

  private void onSaveCurrentClicked(Button button) {
    minecraft.setScreen(ProfileEditScreen.createNew(this, this::onProfileSaved));
  }

  private void openSaveCurrent() {
    onSaveCurrentClicked(null);
  }

  private void onProfileSaved(StoredProfile profile) {
    profileList.refreshProfiles();
    if (profile != null) {
      profileList.selectProfile(profile.id);
    }
  }

  private void onProfileRename(StoredProfile profile) {
    if (profile == null) return;
    minecraft.setScreen(ProfileEditScreen.createRename(this, profile, this::onProfileRenamed));
  }

  private void onProfileRenamed(StoredProfile profile) {
    profileList.refreshProfiles();
    if (profile != null) {
      profileList.selectProfile(profile.id);
    }
  }

  private void onProfileEdit(StoredProfile profile) {
    if (profile == null) return;

    minecraft.setScreen(
        (Screen)
            ClothConfigScreen.createScreen(
                this,
                profile.data,
                () -> {
                  profile.modifiedAt = System.currentTimeMillis();
                  ProfileManager.save();
                  profileList.refreshProfiles();
                }));
  }

  private void onProfileDeleteRequest(StoredProfile profile) {
    if (profile == null) return;
    pendingDeleteProfile = profile;
    minecraft.setScreen(
        new ConfirmScreen(
            this::onDeleteConfirmed,
            Component.literal("Delete profile?"),
            Component.literal(
                "Permanently delete \"" + profile.name + "\"? This cannot be undone."),
            Component.literal("Delete"),
            Component.literal("Cancel")));
  }

  private void onDeleteConfirmed(boolean confirmed) {
    if (confirmed && pendingDeleteProfile != null) {
      ProfileManager.deleteProfile(pendingDeleteProfile.id);
    }
    pendingDeleteProfile = null;
    minecraft.setScreen(this);
  }

  private void onResetClicked(Button button) {
    // Get all builtin profiles
    java.util.List<StoredProfile> builtInProfiles = BuiltInProfiles.all();
    java.util.List<StoredProfile> allProfiles = ProfileManager.getAllProfiles();

    // For each builtin profile, check if there's a profile with same name
    for (StoredProfile builtIn : builtInProfiles) {
      boolean found = false;

      // Find profile with matching name
      for (StoredProfile existing : allProfiles) {
        if (existing.name.equalsIgnoreCase(builtIn.name)) {
          // Restore the builtin profile's data to the existing profile
          existing.data = builtIn.data.copy();
          existing.modifiedAt = System.currentTimeMillis();
          found = true;
          break;
        }
      }

      // If no profile with matching name found, add builtin profile at beginning
      if (!found) {
        ProfileManager.addProfileAtStart(
            builtIn.id, builtIn.name, builtIn.description, builtIn.data.copy());
        // Refresh allProfiles to include the newly added profile
        allProfiles = ProfileManager.getAllProfiles();
      }
    }

    // Save changes and refresh the list
    ProfileManager.save();
    profileList.refreshProfiles();
  }

  private void onExportClicked(Button button) {
    exportButton.active = false;
    setStatus("Opening save dialog...", true);
    DataBundleExporter.pickExportFile()
        .thenAccept(
            path -> {
              if (path == null) {
                Minecraft.getInstance()
                    .execute(
                        () -> {
                          exportButton.active = true;
                          clearStatus();
                        });
                return;
              }
              try {
                DataBundleExporter.exportToFile(path);
                Minecraft.getInstance()
                    .execute(
                        () -> {
                          exportButton.active = true;
                          setStatus("Exported to " + path.getFileName(), true);
                        });
              } catch (Exception e) {
                Minecraft.getInstance()
                    .execute(
                        () -> {
                          exportButton.active = true;
                          setStatus("Export failed: " + e.getMessage(), false);
                        });
              }
            });
  }

  private void onImportClicked(Button button) {
    importButton.active = false;
    setStatus("Opening file dialog...", true);
    DataBundleExporter.pickImportFile()
        .thenAccept(
            path -> {
              if (path == null) {
                Minecraft.getInstance()
                    .execute(
                        () -> {
                          importButton.active = true;
                          clearStatus();
                        });
                return;
              }
              DataBundleExporter.ImportResult result = DataBundleExporter.importFromFile(path);
              Minecraft.getInstance()
                  .execute(
                      () -> {
                        importButton.active = true;
                        setStatus(result.message(), result.success());
                        if (result.success()) {
                          profileList.refreshProfiles();
                        }
                      });
            });
  }

  private void setStatus(String message, boolean success) {
    this.statusMessage = message;
    this.statusSuccess = success;
    this.statusMessageExpiry = System.currentTimeMillis() + 5000;
  }

  private void clearStatus() {
    this.statusMessage = null;
  }

  private void onHistoryClicked(Button button) {
    minecraft.setScreen(new HistoryScreen(this));
  }

  private void onReportsClicked(Button button) {
    minecraft.setScreen(new RideReportListScreen(this));
  }

  private void onCloseClicked(Button button) {
    onClose();
  }

  private void onSettingsClicked(Button button) {
    minecraft.setScreen((Screen) ClothConfigScreen.createScreen(this));
  }

  private void openSettings() {
    onSettingsClicked(null);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    renderDarkBackground(graphics);

    // Draw title
    Component title =
        Component.literal("ImagineMoreFun").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA);
    graphics.drawCenteredString(font, title, width / 2, 8, LABEL_COLOR);

    // Draw progress bars below title, all in same row divided by 3
    int progressBarY = 30;
    int sectionWidth = width / 3;

    // 1k progress bar (left section)
    ProgressData progress1k = calculateProgress(1000);
    Component bar1k = createProgressBar("1k", progress1k);
    graphics.drawCenteredString(font, bar1k, sectionWidth / 2, progressBarY, LABEL_COLOR);

    // 5k progress bar (middle section)
    ProgressData progress5k = calculateProgress(5000);
    Component bar5k = createProgressBar("5k", progress5k);
    graphics.drawCenteredString(font, bar5k, (int) (sectionWidth * 1.5), progressBarY, LABEL_COLOR);

    // 10k progress bar (right section)
    ProgressData progress10k = calculateProgress(10000);
    Component bar10k = createProgressBar("10k", progress10k);
    graphics.drawCenteredString(
        font, bar10k, (int) (sectionWidth * 2.5), progressBarY, LABEL_COLOR);

    if (currentSettingsEntry != null) {
      currentSettingsEntry.render(graphics, mouseX, mouseY);
    }

    int footerY = height - FOOTER_HEIGHT;
    graphics.fill(0, footerY, width, height, 0xDD000000);

    // Render status message above footer
    if (statusMessage != null && System.currentTimeMillis() < statusMessageExpiry) {
      int statusColor = statusSuccess ? 0xFF55FF55 : 0xFFFF5555;
      graphics.drawCenteredString(font, statusMessage, width / 2, footerY - 12, statusColor);
    } else if (statusMessage != null) {
      statusMessage = null;
    }

    super.render(graphics, mouseX, mouseY, delta);
  }

  private Component createProgressBar(String label, ProgressData data) {
    net.minecraft.network.chat.MutableComponent progressBar =
        Component.literal("[" + label + "] ").withStyle(ChatFormatting.WHITE);

    progressBar.append(Component.literal(" ").withStyle(ChatFormatting.WHITE));
    progressBar.append(
        Component.literal(String.format("%.2f%%", data.percentage()))
            .withStyle(ChatFormatting.YELLOW));
    progressBar.append(Component.literal(" (").withStyle(ChatFormatting.WHITE));
    progressBar.append(
        Component.literal(TimeFormatUtil.formatDuration(data.timeRemainingSeconds()))
            .withStyle(ChatFormatting.WHITE));
    progressBar.append(Component.literal(")").withStyle(ChatFormatting.WHITE));

    return progressBar;
  }

  private void renderDarkBackground(GuiGraphics graphics) {
    graphics.fill(0, 0, this.width, this.height, 0xCC000000);
  }

  private ProgressData calculateProgress(int goal) {
    RideCountManager countManager = RideCountManager.getInstance();
    long totalSecondsNeeded = 0;
    long totalSecondsFromZero = 0;
    long completedSeconds = 0;

    for (RideName ride : RideName.sortedByDisplayName()) {

      if (ModConfig.currentSetting.hiddenRides.contains(ride.toMatchString())) {
        continue;
      }

      int currentCount = countManager.getRideCount(ride);
      int rideTimeSeconds = ride.getRideTime();

      if (rideTimeSeconds >= 99999) {
        continue;
      }

      totalSecondsFromZero += (long) goal * rideTimeSeconds;

      if (currentCount >= goal) {
        completedSeconds += (long) goal * rideTimeSeconds;
      } else {
        completedSeconds += (long) currentCount * rideTimeSeconds;
        int ridesNeeded = goal - currentCount;
        totalSecondsNeeded += (long) ridesNeeded * rideTimeSeconds;
      }
    }

    double progressPercentage = 0.0;
    if (totalSecondsFromZero > 0) {
      progressPercentage = ((double) completedSeconds / totalSecondsFromZero) * 100.0;
    }

    return new ProgressData(progressPercentage, totalSecondsNeeded);
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (currentSettingsEntry != null) {
      if (currentSettingsEntry.mouseClicked(event)) {
        return true;
      }
    }
    return super.mouseClicked(event, doubleClick);
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return true;
  }

  @Override
  public void onClose() {
    if (minecraft != null) {
      minecraft.setScreen(parent);
    }
  }
}
