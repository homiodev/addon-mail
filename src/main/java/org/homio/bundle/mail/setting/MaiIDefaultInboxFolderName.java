package org.homio.bundle.mail.setting;

import org.homio.bundle.api.setting.SettingPluginText;

public class MaiIDefaultInboxFolderName implements SettingPluginText {

  @Override
  public String getDefaultValue() {
    return "INBOX";
  }

  @Override
  public int order() {
    return 100;
  }
}
