package org.homio.addon.mail.setting;

import org.homio.api.setting.SettingPluginTextInput;
import org.jetbrains.annotations.NotNull;

public class MaiIDefaultInboxFolderName implements SettingPluginTextInput {

  @Override
  public @NotNull String getDefaultValue() {
    return "INBOX";
  }

  @Override
  public int order() {
    return 100;
  }
}
