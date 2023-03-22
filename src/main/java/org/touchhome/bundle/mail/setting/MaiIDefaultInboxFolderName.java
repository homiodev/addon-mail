package org.touchhome.bundle.mail.setting;

import org.touchhome.bundle.api.setting.SettingPluginText;

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
