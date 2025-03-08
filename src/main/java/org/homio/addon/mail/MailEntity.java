package org.homio.addon.mail;

import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.homio.api.Context;
import org.homio.api.entity.BaseEntity;
import org.homio.api.entity.HasJsonData;
import org.homio.api.entity.types.CommunicationEntity;
import org.homio.api.model.Icon;
import org.homio.api.model.JSON;
import org.homio.api.model.OptionModel.KeyValueEnum;
import org.homio.api.model.UpdatableValue;
import org.homio.api.service.EntityService;
import org.homio.api.ui.UISidebarChildren;
import org.homio.api.ui.field.UIField;
import org.homio.api.ui.field.UIFieldGroup;
import org.homio.api.ui.field.UIFieldSlider;
import org.homio.api.ui.field.action.HasDynamicUIFields;
import org.homio.api.ui.field.action.v1.UIInputBuilder;
import org.homio.api.util.CommonUtils;
import org.homio.api.util.Lang;
import org.homio.api.util.SecureString;
import org.homio.api.widget.CustomWidgetDataStore;
import org.homio.api.widget.HasCustomWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

@SuppressWarnings({"JpaAttributeMemberSignatureInspection", "JpaAttributeTypeInspection", "ClassEscapesDefinedScope", "unused"})
@Getter
@Setter
@Entity
@Accessors(chain = true)
@UISidebarChildren(icon = "fas fa-envelope", color = "#CC3300")
public class MailEntity extends CommunicationEntity implements HasCustomWidget,
  EntityService<MailService> {

  @Override
  public @Nullable Map<String, CallServiceMethod> getCallServices() {
    return Map.of(
      "getFullMailBody",
      (context, params) -> getService().getFullMailBody(params.get("id").asText()),
      "deleteMail",
      (context, params) -> getService().deleteMail(params.get("id").asText()),
    "sendMail",
      (context, params) -> getService().sendMail(
        params.get("to").asText(),
        params.get("subject").asText(),
        params.get("body").asText(),
        (ArrayNode)params.path("files")));

  }

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
  @UIFieldSlider(min = 10, max = 600)
  public int getPop3RefreshTime() {
    return getJsonData("pop3_refresh_time", 60);
  }

  public void setPop3RefreshTime(@Min(10) int value) {
    setJsonData("pop3_refresh_time", value);
  }

  @UIField(order = 300)
  @UIFieldGroup("GENERAL")
  public String getDefFolder() {
    return getJsonData("def_f", "INBOX");
  }

  public void setDefFolder(String value) {
    setJsonData("def_f", value);
  }

  @Override
  public String getDefaultName() {
    return "MailBot";
  }

  @Override
  public void assembleActions(UIInputBuilder uiInputBuilder) {

  }

  @Override
  public long getEntityServiceHashCode() {
    return getJsonDataHashCode("pop3_hostname", "pop3_password",
      "pop3_user", "smtp_hostname", "smtp_user", "smtp_password");
  }

  @Override
  public @NotNull Class<MailService> getEntityServiceItemClass() {
    return MailService.class;
  }

  @Override
  public @Nullable MailService createService(@NotNull Context context) {
    return new MailService(context, this);
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
    public @NotNull String getValue() {
      return title;
    }

    public Properties prepareMail(MailEntity mailEntity) {
      Properties props = new Properties();
      props.put("mail.smtp.host", mailEntity.getSmtpHostname());
      props.put("mail.smtp.port", mailEntity.getSmtpPort());
      props.put("mail.smtp.auth", "true");

      switch (this) {
        case SSL:
          props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
          props.put("mail.smtp.socketFactory.port", mailEntity.getSmtpPort());
          props.put("mail.smtp.ssl.enable", "true");
          break;
        case START_TLS:
          props.put("mail.smtp.starttls.enable", "true");
          break;
        case PLAIN:
          break;
      }
      return props;
    }
  }

  @Override
  public void assembleUIFields(@NotNull HasDynamicUIFields.UIFieldBuilder uiFieldBuilder,
                               @NotNull HasJsonData sourceEntity) {
    var folder = UpdatableValue.wrap(sourceEntity, getDefFolder(), "folder");
    uiFieldBuilder.addInput(1, folder);
  }

  @Override
  public void setWidgetDataStore(@NotNull CustomWidgetDataStore customWidgetDataStore, @NotNull String
    widgetEntityID, @NotNull JSON widgetData) {
    getService().setWidgetDataStore(customWidgetDataStore, widgetEntityID, widgetData);
  }

  @Override
  public void removeWidgetDataStore(@NotNull String widgetEntityID) {
    getService().removeWidgetDataStore(widgetEntityID);
  }

  @Override
  public @NotNull BaseEntity createWidget(@NotNull Context context, @NotNull String name, @NotNull String tabId,
                                          int width, int height) {
    return context
      .widget()
      .createCustomWidget(
        getEntityID(),
        tabId,
        builder ->
          builder
            .code(CommonUtils.readFile("code.js"))
            .css(CommonUtils.readFile("style.css"))
            .parameterEntity(getEntityID()));
  }

  @Override
  public @Nullable Map<String, Icon> getAvailableWidgets() {
    return Map.of("Mail", new Icon("fas fa-envelope", "#CC3300"));
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
