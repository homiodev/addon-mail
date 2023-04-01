package org.homio.bundle.mail;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.activation.FileDataSource;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.DefaultAuthenticator;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailAttachment;
import org.apache.commons.mail.EmailConstants;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.HtmlEmail;

@Log4j2
public class MailBuilder {

  private final MailEntity mailEntity;
  private final List<InternetAddress> recipients = new ArrayList<>();
  private final List<URL> attachmentURLs = new ArrayList<>();
  private final List<File> attachmentFiles = new ArrayList<>();
  private final String subject;
  private final String html;

  public MailBuilder(MailEntity mailEntity, String subject, String html, String recipients) throws AddressException {
    this.mailEntity = mailEntity;
    this.subject = StringUtils.defaultString(subject, "(no subject)");
    this.html = StringUtils.defaultString(html, "(no body)");
    this.recipients.addAll(Arrays.asList(InternetAddress.parse(recipients)));
  }

  public void withURLAttachment(String urlString) throws MalformedURLException {
    attachmentURLs.add(new URL(urlString));
  }

  public void withFileAttachment(String path) {
    attachmentFiles.add(new File(path));
  }

  public boolean sendMail() {
    try {
      Email mail;
      // html email
      HtmlEmail htmlMail = new HtmlEmail();
      htmlMail.setCharset(EmailConstants.UTF_8);
      htmlMail.setMsg(html);
      for (File file : attachmentFiles) {
        htmlMail.attach(new FileDataSource(file), "", "");
      }
      for (URL url : attachmentURLs) {
        EmailAttachment attachment = new EmailAttachment();
        attachment.setURL(url);
        attachment.setDisposition(EmailAttachment.ATTACHMENT);
        htmlMail.attach(attachment);
      }
      mail = htmlMail;

      mail.setTo(recipients);
      mail.setSubject(subject);
      mail.setFrom(mailEntity.getSender());

      mail.setHostName(mailEntity.getSmtpHostname());
      mailEntity.getSmtpSecurity().prepareMail(mail, mailEntity);

      if (!mailEntity.getSmtpUser().isEmpty() && !mailEntity.getSmtpPassword().asString().isEmpty()) {
        mail.setAuthenticator(
            new DefaultAuthenticator(mailEntity.getSmtpUser(), mailEntity.getSmtpPassword().asString()));
      }
      mail.send();
    } catch (EmailException e) {
      log.warn("{}", e.getMessage());
      if (e.getCause() != null) {
        log.warn("{}", e.getCause().toString());
      }
      return false;
    }
    return true;
  }
}
