package org.homio.addon.mail;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pivovarit.function.ThrowingConsumer;
import com.pivovarit.function.ThrowingFunction;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.eclipse.angus.mail.util.BASE64DecoderStream;
import org.homio.api.Context;
import org.homio.api.ContextBGP;
import org.homio.api.model.JSON;
import org.homio.api.service.EntityService;
import org.homio.api.widget.CustomWidgetDataStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.homio.api.util.JsonUtils.OBJECT_MAPPER;

public class MailService extends EntityService.ServiceInstance<MailEntity> {

  private final Map<String, ThrowingConsumer<Store, Exception>> registeredHandlers = new HashMap<>();
  private final Map<String, WidgetInfo> widgetListeners = new ConcurrentHashMap<>();

  private final Map<String, Set<MessageWrapper>> folderMessages = new ConcurrentHashMap<>();
  private ContextBGP.ThreadContext<Void> mailListeners;
  private Long lastCheckedTimestamp;

  public MailService(@NotNull Context context, @NotNull MailEntity entity) {
    super(context, entity, false, "Mail");
  }

  @Override
  public void destroy(boolean forRestart, @Nullable Exception ex) {
    ContextBGP.cancel(mailListeners);
  }

  @Override
  protected void testService() {
    connectToMailServerAndHandle(store -> null);
  }

  @Override
  protected void initialize() {
    connectToMailServerAndHandle(store -> null);
  }

  @SneakyThrows
  private <T> T connectToMailServerAndHandle(ThrowingFunction<Store, T, Exception> handler) {
    Session session = getSession();

    try (Store store = session.getStore()) {
      store.connect(entity.getPop3Hostname(), entity.getPop3Port(),
        entity.getPop3User(), entity.getPop3Password().asString());
      return handler.apply(store);
    }
  }

  private @NotNull Session getSession() {
    String baseProtocol = entity.getMailFetchProtocolType().name().toLowerCase();
    String protocol = entity.getPop3Security() == MailEntity.Security.SSL ? baseProtocol.concat("s") : baseProtocol;

    Properties props = new Properties();
    props.setProperty("mail." + baseProtocol + ".starttls.enable", "true");
    props.setProperty("mail.store.protocol", protocol);
    Session session = Session.getInstance(props);
    return session;
  }

  public void setWidgetDataStore(
    CustomWidgetDataStore widgetDataStore,
    @NotNull String widgetEntityID,
    @NotNull JSON widgetData) {
    widgetListeners.put(widgetEntityID, new WidgetInfo(widgetDataStore, widgetData));
    createMailListenerIfRequire();
    setWidgetDataToUI();
  }

  public void removeWidgetDataStore(@NotNull String widgetEntityID) {
    widgetListeners.remove(widgetEntityID);
    createMailListenerIfRequire();
  }

  private void createMailListenerIfRequire() {
    if (registeredHandlers.isEmpty() && widgetListeners.isEmpty()) {
      ContextBGP.cancel(mailListeners);
      mailListeners = null;
      return;
    }
    if (mailListeners != null) {
      return;
    }
    mailListeners =
      context
        .bgp()
        .builder("read-mails")
        .delay(Duration.ofSeconds(10))
        .interval(Duration.ofSeconds(entity.getPop3RefreshTime()))
        .execute(() -> connectToMailServerAndHandle(store -> {
          Set<String> folders = widgetListeners.values()
            .stream()
            .map(s -> s.widgetData.optString("folder", entity.getDefFolder()))
            .collect(Collectors.toSet());
          folders.add(entity.getDefFolder());
          try {
            for (String folder : folders) {
              try (Folder mailbox = store.getFolder(folder)) {
                mailbox.open(Folder.READ_ONLY);
                Message[] messages;
                if (lastCheckedTimestamp != null) {
                  SearchTerm searchTerm = new ReceivedDateTerm(ComparisonTerm.GT, new Date(lastCheckedTimestamp));
                  messages = mailbox.search(searchTerm);
                } else {
                  messages = mailbox.getMessages();
                }
                var emails = folderMessages.computeIfAbsent(folder, k -> new LinkedHashSet<>());
                AtomicInteger count = new AtomicInteger(0);
                Arrays.stream(messages).parallel().limit(10)
                  .forEach(message -> {
                    try {
                      var msg = new MessageWrapper(getMessageUID(message), message.getSubject(), folder, message.getFrom()[0].toString(),
                        message.getDescription(), message.getMessageNumber(), message.getReceivedDate(), message.getSize(), message.isSet(Flags.Flag.SEEN),
                        getAttachments(message));
                      if (message.isMimeType("multipart/*")) {
                        Multipart multipart = (Multipart) message.getContent();
                        for (int i = 0; i < multipart.getCount(); i++) {
                          BodyPart part = multipart.getBodyPart(i);
                          if (part.isMimeType("text/plain")) {
                            msg.preview = part.getContent().toString();
                          }
                        }
                      }
                      emails.add(msg);
                    } catch (Exception e) {
                      log.error("Error while reading mail", e);
                    }
                    log.info("Processed {}/{} mail", count.incrementAndGet(), messages.length);
                  });
              }
            }
            lastCheckedTimestamp = System.currentTimeMillis();
            setWidgetDataToUI();
          } catch (Exception e) {
            log.error("Error while reading mails", e);
          }

          for (ThrowingConsumer<Store, Exception> handler : registeredHandlers.values()) {
            handler.accept(store);
          }
          return 0;
        }));
  }

  private static void readMessageBody(Message message, MessageWrapper msg) throws Exception {
    if (message.isMimeType("text/plain") || message.isMimeType("text/html")) {
      msg.setBody(message.getContent(), true);
    } else if (message.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) message.getContent();
      for (int i = 0; i < multipart.getCount(); i++) {
        BodyPart part = multipart.getBodyPart(i);
        if (part.isMimeType("text/plain")) {
          msg.setBody(part.getContent(), true);
        } else if (part.isMimeType("text/html")) {
          msg.setBody(part.getContent(), false);
        } else if (part.getContentType().toLowerCase().contains("multipart/alternative")) {
          Multipart innerMultipart = (Multipart) part.getContent();
          for (int j = 0; j < innerMultipart.getCount(); j++) {
            BodyPart innerPart = innerMultipart.getBodyPart(j);
            String contentType = innerPart.getContentType().toLowerCase();

            if (contentType.contains("text/plain")) {
              msg.setBody(innerPart.getContent(), true);
            } else if (contentType.contains("text/html")) {
              msg.setBody(innerPart.getContent(), false);
              saveInlineImages(innerPart, msg);
            }
          }
        } else if (part.isMimeType("image/*")) {
          saveInlineImage(part, msg);
        }
      }
    }
    if (msg.preview != null && msg.preview.length() > 256) {
      msg.preview = msg.preview.substring(0, 256) + "...";
    }
  }

  private static void saveInlineImages(BodyPart part, MessageWrapper msg) throws Exception {
    if (part.getContentType().toLowerCase().contains("image")) {
      saveInlineImage(part, msg);
    }
  }

  private static void saveInlineImage(BodyPart part, MessageWrapper msg) throws MessagingException, IOException {
    String contentId = part.getHeader("Content-ID")[0];
    if (contentId != null) {
      contentId = contentId.replaceAll("<", "").replaceAll(">", "");
      try (BASE64DecoderStream base64Stream = (BASE64DecoderStream) part.getContent()) {
        byte[] imageData = base64Stream.readAllBytes();
        msg.images.put(contentId, Base64.getEncoder().encodeToString(imageData));
      }
    }
  }

  private static Set<String> getAttachments(Message message) throws Exception {
    Set<String> attachedFiles = new HashSet<>();
    if (message.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) message.getContent();
      for (int i = 0; i < multipart.getCount(); i++) {
        BodyPart part = multipart.getBodyPart(i);
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
          attachedFiles.add(part.getFileName());
        }
      }
    }
    return attachedFiles;
  }

  public void setWidgetDataToUI() {
    for (WidgetInfo info : widgetListeners.values()) {
      String folder = info.widgetData.optString("folder", entity.getDefFolder());
      info.store.update(folderMessages.getOrDefault(folder, Set.of()));
    }
  }

  public int getMailCount(MailCountFilter mailCountFilter, String folder) {
    return connectToMailServerAndHandle(store -> {
      try (Folder mailbox = store.getFolder(Objects.toString(folder, store.getDefaultFolder().getName()))) {
        mailbox.open(Folder.READ_ONLY);
        return mailCountFilter.countFn.apply(mailbox);
      }
    });
  }

  public void registerHandler(String key, ThrowingConsumer<Store, Exception> handler) {
    registeredHandlers.put(key, handler);
    createMailListenerIfRequire();
  }

  public void releaseHandler(String key) {
    registeredHandlers.remove(key);
    createMailListenerIfRequire();
  }

  public @Nullable JsonNode getFullMailBody(String id) {
    for (Set<MessageWrapper> messages : folderMessages.values()) {
      for (MessageWrapper message : messages) {
        if (message.id.equals(id)) {
          if (message.fullBody == null) {
            connectToMailServerAndHandle(store -> {
              var folder = store.getFolder(message.folder);
              folder.open(message.seen ? Folder.READ_ONLY : Folder.READ_WRITE);
              Message msg = folder.getMessage(message.num);
              if (msg != null) {
                readMessageBody(msg, message);
              }
              if (!message.seen) {
                msg.setFlag(Flags.Flag.SEEN, true);
                folder.close(true);
              }
              return null;
            });
          }
          var mail = OBJECT_MAPPER.createObjectNode();
          String text = message.fullBody;
          if (text != null && !message.asPlainText) {
            for (Map.Entry<String, String> entry : message.images.entrySet()) {
              text = text.replaceAll(
                "cid:" + entry.getKey(),
                "data:image/jpeg;base64," + entry.getValue());
            }
          }
          mail.put("plainText", message.asPlainText);
          mail.put("body", text == null ? message.preview : text);
          return mail;
        }
      }
    }
    return null;
  }

  @SneakyThrows
  public @Nullable JsonNode deleteMail(String id) {
    for (Set<MessageWrapper> messages : folderMessages.values()) {
      MessageWrapper foundMessage = messages.stream().filter(m -> m.id.equals(id)).findFirst()
        .orElseThrow((Supplier<Throwable>) () -> new IllegalArgumentException("Mail not found: " + id));
      connectToMailServerAndHandle(store -> {
        Folder folder = store.getFolder(foundMessage.folder);
        folder.open(Folder.READ_WRITE);
        Message msg = folder.getMessage(foundMessage.num);
        if (msg != null) {
          msg.setFlag(Flags.Flag.DELETED, true);
          folder.close(true);
        }
        return null;
      });
      messages.remove(foundMessage);
      setWidgetDataToUI();
    }
    return null;
  }

  @SneakyThrows
  public @Nullable JsonNode sendMail(String to, String subject, String body, ArrayNode files) {
    MailBuilder builder = new MailBuilder(entity, subject, body, to);
    if(files != null) {
      for (JsonNode file : files) {
        builder.withFileAttachment(file.get("name").asText(), file.get("content").binaryValue());
      }
    }
    builder.sendMail();
    context.ui().toastr().success("Mail sent to: " + to);
    return null;
  }

  @RequiredArgsConstructor
  public enum MailCountFilter {
    total(Folder::getMessageCount),
    recent(folder -> folder.search(new FlagTerm(new Flags(Flags.Flag.RECENT), true)).length),
    deleted(folder -> folder.search(new FlagTerm(new Flags(Flags.Flag.DELETED), true)).length),
    unread(folder -> folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false)).length);
    private final ThrowingFunction<Folder, Integer, Exception> countFn;
  }

  private record WidgetInfo(CustomWidgetDataStore store, JSON widgetData) {
  }

  @Getter
  @RequiredArgsConstructor
  public static final class MessageWrapper {
    private final String id;
    private final String subject;
    private final String folder;
    private final String sender;
    private final String description;
    private final int num;
    private final Date receivedDate;
    private final int size;
    private final boolean seen;
    private final Set<String> attachments;
    private String preview;
    @JsonIgnore
    private String fullBody;
    @JsonIgnore
    private final Map<String, String> images = new HashMap<>();
    @JsonIgnore
    public boolean asPlainText;

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;
      MessageWrapper that = (MessageWrapper) o;
      return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(id);
    }

    @SneakyThrows
    public void setBody(Object html, boolean plainText) {
      String body = html.toString();
      if (plainText) {
        this.fullBody = "<html><body>" + body.replace("\r\n", "<br>").replace("\n", "<br>") + "</body></html>";
      } else {
        this.fullBody = body.replaceAll("(?i)<br\\s*/?>", "");
      }
      this.asPlainText = plainText;
    }
  }

  @SneakyThrows
  private static String getMessageUID(Message message) {
    if (message instanceof MimeMessage mimeMessage) {
      return mimeMessage.getMessageID();
    }
    return message.getFrom()[0].toString() + message.getMessageNumber();
  }
}
