package org.homio.addon.mail;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Log4j2
public class MailBuilder {

  private final MailEntity mailEntity;
  private final List<InternetAddress> recipients = new ArrayList<>();
  private final List<URL> attachmentURLs = new ArrayList<>();
  private final Map<String, DataSource> attachmentFiles = new HashMap<>();
  private final String subject;
  private final String html;

  public MailBuilder(MailEntity mailEntity, String subject, String html, String recipients) throws AddressException {
    this.mailEntity = mailEntity;
    this.subject = Objects.toString(subject, "(no subject)");
    this.html = Objects.toString(html, "(no body)");
    this.recipients.addAll(Arrays.asList(InternetAddress.parse(recipients)));
  }

  public void withURLAttachment(String urlString) throws MalformedURLException {
    attachmentURLs.add(new URL(urlString));
  }

  public void withFileAttachment(String name, byte[] contents) {
    attachmentFiles.put(name, new ByteArrayDataSource(contents, "application/octet-stream"));
  }

  public void withFileAttachment(String path) {
    File file = new File(path);
    attachmentFiles.put(file.getName(), new FileDataSource(file));
  }

  @SneakyThrows
  public void sendMail() {
    var props = mailEntity.getSmtpSecurity().prepareMail(mailEntity);
    Session session = Session.getInstance(props, new Authenticator() {
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(mailEntity.getSmtpUser(), mailEntity.getSmtpPassword().asString());
      }
    });

    Message message = new MimeMessage(session);
    message.setFrom(new InternetAddress(mailEntity.getSender()));
    message.setRecipients(Message.RecipientType.TO, recipients.toArray(new Address[0]));
    message.setSubject(subject);

    MimeMultipart multipart = new MimeMultipart();

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(html, "text/html; charset=utf-8");
    multipart.addBodyPart(htmlPart);

    for (Map.Entry<String, DataSource> entry : attachmentFiles.entrySet()) {
      MimeBodyPart filePart = new MimeBodyPart();
      filePart.setDataHandler(new DataHandler(entry.getValue()));
      filePart.setFileName(entry.getKey());
      multipart.addBodyPart(filePart);
    }

    for (URL url : attachmentURLs) {
      MimeBodyPart urlPart = new MimeBodyPart();
      urlPart.setDataHandler(new DataHandler(url));
      urlPart.setFileName(url.getPath());
      multipart.addBodyPart(urlPart);
    }

    message.setContent(multipart);

    Transport.send(message);
    log.info("Email sent successfully!");
  }
}