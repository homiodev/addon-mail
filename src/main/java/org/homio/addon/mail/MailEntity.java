package org.homio.addon.mail;

import jakarta.persistence.Entity;
import jakarta.validation.constraints.Min;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.Email;
import org.homio.api.entity.types.CommunicationEntity;
import org.homio.api.model.OptionModel.KeyValueEnum;
import org.homio.api.ui.UISidebarChildren;
import org.homio.api.ui.field.UIField;
import org.homio.api.ui.field.UIFieldGroup;
import org.homio.api.util.Lang;
import org.homio.api.util.SecureString;
import org.jetbrains.annotations.NotNull;

@SuppressWarnings({"JpaAttributeMemberSignatureInspection", "JpaAttributeTypeInspection"})
@Getter
@Setter
@Entity
@Accessors(chain = true)
@UISidebarChildren(icon = "fas fa-envelope", color = "#CC3300")
public class MailEntity extends CommunicationEntity {

  @Override
  public String getDescriptionImpl() {
    if (StringUtils.isEmpty(getSender())
        || StringUtils.isEmpty(getSmtpHostname())
        || StringUtils.isEmpty(getSmtpUser())
        || StringUtils.isEmpty(getSmtpPassword())
        || StringUtils.isEmpty(getPop3Hostname())
        || StringUtils.isEmpty(getPop3Password())
        || StringUtils.isEmpty(getPop3User())) {
      return Lang.getServerMessage("MAIL.DESCRIPTION");
    }
    return null;
  }

  @Override
  protected @NotNull String getDevicePrefix() {
    return "mail";
  }

  @UIField(order = 10, inlineEdit = true)
  public PredefinedMailType getPredefinedMailType() {
    return getJsonDataEnum("def_type", PredefinedMailType.Gmail);
  }

  public void setPredefinedMailType(PredefinedMailType value) {
    setJsonData("def_type", value);
  }

  @UIField(order = 30, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("SMTP")
  public String getSender() {
    return getJsonData("sender");
  }

  public void setSender(String value) {
    setJsonData("sender", value);
  }

  @UIField(order = 40, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("SMTP")
  public String getSmtpHostname() {
    return getJsonData("smtp_hostname", getPredefinedMailType().smtpHostname);
  }

  public void setSmtpHostname(String value) {
    setJsonData("smtp_hostname", value);
  }

  @UIField(order = 50)
  @UIFieldGroup("SMTP")
  public int getSmtpPort() {
    return getJsonData("smtp_port", getSmtpSecurity() == Security.SSL ? 465 : 25);
  }

  public void setSmtpPort(int value) {
    setJsonData("smtp_port", value);
  }

  @UIField(order = 60)
  @UIFieldGroup("SMTP")
  public Security getSmtpSecurity() {
    return getJsonDataEnum("smtp_security", getPredefinedMailType().smtpSecurity);
  }

  public void setSmtpSecurity(Security value) {
    setJsonData("smtp_security", value);
  }

  @UIField(order = 70, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("SMTP")
  public String getSmtpUser() {
    return getJsonData("smtp_user", "");
  }

  public void setSmtpUser(String value) {
    setJsonData("smtp_user", value);
  }

  @UIField(order = 80, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("SMTP")
  public SecureString getSmtpPassword() {
    return getJsonSecure("smtp_password", "");
  }

  public void setSmtpPassword(String value) {
    setJsonDataSecure("smtp_password", value);
  }

  @UIField(order = 100)
  public FetchProtocolType getMailFetchProtocolType() {
    return getJsonDataEnum("fetch_protocol", FetchProtocolType.IMAP);
  }

  public void setMailFetchProtocolType(FetchProtocolType value) {
    setJsonData("fetch_protocol", value);
  }

  @UIField(order = 200, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("POP3/IMAP")
  public String getPop3User() {
    return getJsonData("pop3_user", "");
  }

  public void setPop3User(String value) {
    setJsonData("pop3_user", value);
  }

  @UIField(order = 210, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("POP3/IMAP")
  public SecureString getPop3Password() {
    return getJsonSecure("pop3_password", "");
  }

  public void setPop3Password(String value) {
    setJsonData("pop3_password", value);
  }

  @UIField(order = 220, required = true, inlineEditWhenEmpty = true)
  @UIFieldGroup("POP3/IMAP")
  public String getPop3Hostname() {
    return getJsonData("pop3_hostname", getPredefinedMailType().imapHostname);
  }

  public void setPop3Hostname(String value) {
    setJsonData("pop3_hostname", value);
  }

  @UIField(order = 230)
  @UIFieldGroup("POP3/IMAP")
  public int getPop3Port() {
    return getJsonData("pop3_port", getMailFetchProtocolType().defaultPortFn.apply(this));
  }

  public void setPop3Port(int value) {
    setJsonData("pop3_port", value);
  }

  @UIField(order = 240)
  @UIFieldGroup("POP3/IMAP")
  public Security getPop3Security() {
    return getJsonDataEnum("pop3_security", getPredefinedMailType().imapSecurity);
  }

  public void setPop3Security(Security value) {
    setJsonData("pop3_security", value);
  }

  @UIField(order = 250)
  @UIFieldGroup("POP3/IMAP")
  public int getPop3RefreshTime() {
    return getJsonData("pop3_refresh_time", 60);
  }

  public void setPop3RefreshTime(@Min(10) int value) {
    setJsonData("pop3_refresh_time", value);
  }

  @Override
  public String getDefaultName() {
    return "MailBot";
  }

  @RequiredArgsConstructor
  public enum FetchProtocolType {
    IMAP(mailEntity -> mailEntity.getPop3Security() == Security.SSL ? 993 : 143),
    POP3(mailEntity -> mailEntity.getPop3Security() == Security.SSL ? 995 : 110);

    private final Function<MailEntity, Integer> defaultPortFn;
  }

  @RequiredArgsConstructor
  public enum Security implements KeyValueEnum {
    PLAIN("plain"),
    START_TLS("StarTTLS"),
    SSL("SSL/TLS");
    private final String title;

    @Override
    public String getValue() {
      return title;
    }

    public void prepareMail(Email mail, MailEntity mailEntity) {
      switch (this) {
        case SSL:
          mail.setSSLOnConnect(true);
          mail.setSslSmtpPort(String.valueOf(mailEntity.getSmtpPort()));
          break;
        case START_TLS:
          mail.setStartTLSEnabled(true);
          mail.setStartTLSRequired(true);
          mail.setSmtpPort(mailEntity.getSmtpPort());
          break;
        case PLAIN:
          mail.setSmtpPort(mailEntity.getSmtpPort());
      }
    }
  }

  @RequiredArgsConstructor
  enum PredefinedMailType {
    None("", Security.PLAIN, "", Security.SSL),
    Gmail("smtp.gmail.com", Security.SSL, "imap.gmail.com", Security.SSL);
    public final String smtpHostname;
    public final Security smtpSecurity;
    public final String imapHostname;
    public final Security imapSecurity;
  }
}
