package org.homio.addon.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.homio.api.AddonConfiguration;
import org.homio.api.AddonEntrypoint;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
@AddonConfiguration
public class MailEntrypoint implements AddonEntrypoint {

  public void init() {

  }
}
