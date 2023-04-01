package org.homio.bundle.mail.workspace;

import com.pivovarit.function.ThrowingBiConsumer;
import com.pivovarit.function.ThrowingConsumer;
import com.pivovarit.function.ThrowingFunction;
import com.pivovarit.function.ThrowingPredicate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeMultipart;
import javax.mail.search.FlagTerm;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.homio.bundle.mail.MailEntrypoint;
import org.homio.bundle.mail.setting.MaiIDefaultInboxFolderName;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.homio.bundle.api.EntityContext;
import org.homio.bundle.api.EntityContextBGP;
import org.homio.bundle.api.state.DecimalType;
import org.homio.bundle.api.state.RawType;
import org.homio.bundle.api.state.State;
import org.homio.bundle.api.workspace.WorkspaceBlock;
import org.homio.bundle.api.workspace.scratch.MenuBlock;
import org.homio.bundle.api.workspace.scratch.Scratch3Block;
import org.homio.bundle.api.workspace.scratch.Scratch3ExtensionBlocks;
import org.homio.bundle.mail.MailBuilder;
import org.homio.bundle.mail.MailEntity;

@Log4j2
@Component
public class Scratch3MailBlocks extends Scratch3ExtensionBlocks {

  private final Map<String, POPHandler> popHandlers = new ConcurrentHashMap<>();

  private final MenuBlock.ServerMenuBlock mailMenu;
  private final MenuBlock.StaticMenuBlock<MailCountFilter> mailCountFilterMenu;

  private final Scratch3Block sendMailCommand;
  private final Scratch3Block attachFileCommand;

  private final Scratch3Block mailCountReporter;
  private final Scratch3Block whenGotMailHat;
  private Integer lastMessageCount;

  public Scratch3MailBlocks(EntityContext entityContext, MailEntrypoint mailEntrypoint) {
    super("#8F4D77", entityContext, mailEntrypoint, null);
    setParent("communication");

    // Menu
    this.mailMenu = menuServerItems("mailEntity", MailEntity.class, "Select Mail");
    this.mailCountFilterMenu = menuStatic("mailCountFilter", MailCountFilter.class, MailCountFilter.total);

    // Hats
    this.whenGotMailHat = withMail(blockHat(20, "get_mail",
        "Get mail (subject [SUBJECT]), (from [FROM]) of [MAIL]", this::whenGotMailHat));
    this.whenGotMailHat.addArgument("SUBJECT", "-");
    this.whenGotMailHat.addArgument("FROM", "receiver@mail.com");
    this.whenGotMailHat.appendSpace();

    // reporters
    this.mailCountReporter = withMail(blockReporter(40, "mails_count",
        "Get [FILTER] mails of [MAIL] in folder [FOLDER]", this::getMailCountReporter));
    this.mailCountReporter.addArgument("FILTER", this.mailCountFilterMenu);
    this.mailCountReporter.addArgument("FOLDER", "INBOX");
    this.mailCountReporter.appendSpace();

    // commands
    this.sendMailCommand = withMail(blockCommand(100, "send_mail",
        "Send mail [TITLE] to [RECIPIENTS] of [MAIL] with body [BODY]", this::sendMailCommand));
    this.sendMailCommand.addArgument("TITLE", "title");
    this.sendMailCommand.addArgument("RECIPIENTS", "receiver@mail.com");
    this.sendMailCommand.addArgument("BODY", "<b>body</b>");

    this.attachFileCommand = blockCommand(130, MailApplyHandler.update_add_file.name(),
        "Attach file[VALUE]", this::skipExpression);
    this.attachFileCommand.addArgument(VALUE, "file");
  }

  private State getMailCountReporter(WorkspaceBlock workspaceBlock) {
    MailEntity mailEntity = getMailEntity(workspaceBlock);
    MailCountFilter mailCountFilter = workspaceBlock.getMenuValue("FILTER", this.mailCountFilterMenu);
    return new DecimalType(connectToMailServerAndHandle(mailEntity, store -> {
      try (Folder mailbox = store.getFolder(StringUtils.defaultString(workspaceBlock.getInputString("FOLDER"),
          store.getDefaultFolder().getName()))) {
        mailbox.open(Folder.READ_ONLY);
        return mailCountFilter.countFn.apply(mailbox);
      }
    }, -1));
  }

  private void whenGotMailHat(WorkspaceBlock workspaceBlock) {
    String subject = workspaceBlock.getInputString("SUBJECT");
    String from = workspaceBlock.getInputString("FROM");
    handleNextWhenGotNewMessages(workspaceBlock, message -> {
      // test from address
      if (!from.isEmpty() && !from.equals("-")) {
        boolean passMessage = false;
        for (Address fromAddress : message.getFrom()) {
          if (fromAddress.toString().contains(from)) {
            passMessage = true;
          }
        }
        if (!passMessage) {
          return false;
        }
      }

      return subject.isEmpty() || subject.equals("-") ||
          StringUtils.defaultString(message.getSubject(), "").contains(subject);
    });
  }

  private void handleNextWhenGotNewMessages(WorkspaceBlock workspaceBlock,
      ThrowingPredicate<Message, Exception> acceptMessage) {
    handleHat(workspaceBlock, mailbox -> {
      // search for new messages
      int messageCount = mailbox.getMessageCount();
      if (lastMessageCount != null && messageCount > lastMessageCount) {
        for (int i = lastMessageCount; i < messageCount; i++) {
          Message message = mailbox.getMessage(i + 1);
          if (acceptMessage.test(message)) {
            lastMessageCount = messageCount;
            return message;
          }
        }
      }
      lastMessageCount = messageCount;
      return null;
    });
  }

  private void handleHat(WorkspaceBlock workspaceBlock, ThrowingFunction<Folder, Message, Exception> handler) {
    workspaceBlock.handleNext(next -> {
      MailEntity mailEntity = getMailEntity(workspaceBlock);

      popHandlers.computeIfAbsent(mailEntity.getEntityID(), s -> new POPHandler(mailEntity));
      popHandlers.get(mailEntity.getEntityID()).registeredHandlers
          .put(workspaceBlock.getBlockId(), store -> {
            try (Folder mailbox = store.getFolder(
                entityContext.setting().getValue(MaiIDefaultInboxFolderName.class))) {
              mailbox.open(Folder.READ_ONLY);
              Message message = handler.apply(mailbox);
              if (message != null) {
                String text = null;
                if (message.isMimeType("text/plain")) {
                  text = message.getContent().toString();
                } else if (message.isMimeType("multipart/*")) {
                  MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
                  text = getTextFromMimeMultipart(mimeMultipart);
                }
                workspaceBlock.setValue(new RawType(StringUtils.defaultString(text, "").getBytes(),
                    MediaType.TEXT_PLAIN_VALUE, message.getSubject()));
                next.handle();
              }
            } catch (Exception ex) {
              log.error("Error when fetching mails from server", ex);
            }
          });

      workspaceBlock.onRelease(() -> {
        POPHandler popHandler = popHandlers.get(mailEntity.getEntityID());
        if (popHandler != null) {
          popHandler.registeredHandlers.remove(workspaceBlock.getBlockId());

          // stop whole fetching
          if (popHandler.registeredHandlers.isEmpty()) {
            popHandler.refreshTask.cancel();
          }
          if (popHandler.registeredHandlers.isEmpty()) {
            popHandlers.remove(mailEntity.getEntityID());
          }
        }
      });
    });
  }

  private MailEntity getMailEntity(WorkspaceBlock workspaceBlock) {
    return workspaceBlock.getMenuValueEntityRequired("MAIL", this.mailMenu);
  }

  private void skipExpression(WorkspaceBlock ignore) {
    // skip expression
  }

  @SneakyThrows
  private void sendMailCommand(WorkspaceBlock workspaceBlock) {
    MailEntity mailEntity = getMailEntity(workspaceBlock);
    MailBuilder mailBuilder = new MailBuilder(mailEntity,
        workspaceBlock.getInputString("TITLE"),
        workspaceBlock.getInputString("BODY"),
        workspaceBlock.getInputString("RECIPIENTS"));
    applyParentBlocks(mailBuilder, workspaceBlock.getParent());

    mailBuilder.sendMail();
  }

  @SneakyThrows
  private void applyParentBlocks(MailBuilder mailBuilder, WorkspaceBlock parent) {
    if (parent == null || !parent.getBlockId().startsWith("mail_update_")) {
      return;
    }
    applyParentBlocks(mailBuilder, parent.getParent());
    MailApplyHandler.valueOf(parent.getOpcode()).applyFn.accept(parent, mailBuilder);
  }

  private Scratch3Block withMail(Scratch3Block scratch3Block) {
    scratch3Block.addArgument("MAIL", this.mailMenu);
    return scratch3Block;
  }

  private <T> T connectToMailServerAndHandle(MailEntity mailEntity, ThrowingFunction<Store, T, Exception> handler,
      T onErrorValue) {
    String baseProtocol = mailEntity.getMailFetchProtocolType().name().toLowerCase();
    String protocol = mailEntity.getPop3Security() == MailEntity.Security.SSL ? baseProtocol.concat("s") : baseProtocol;

    Properties props = new Properties();
    props.setProperty("mail." + baseProtocol + ".starttls.enable", "true");
    props.setProperty("mail.store.protocol", protocol);
    Session session = Session.getInstance(props);

    try (Store store = session.getStore()) {
      store.connect(mailEntity.getPop3Hostname(), mailEntity.getPop3Port(),
          mailEntity.getPop3User(), mailEntity.getPop3Password().asString());
      return handler.apply(store);
    } catch (Exception e) {
      log.error("error when trying to refresh IMAP: {}", e.getMessage());
    }
    return onErrorValue;
  }

  private String getTextFromMimeMultipart(
      MimeMultipart mimeMultipart) throws MessagingException, IOException {
    StringBuilder result = new StringBuilder();
    int count = mimeMultipart.getCount();
    for (int i = 0; i < count; i++) {
      BodyPart bodyPart = mimeMultipart.getBodyPart(i);
      if (bodyPart.isMimeType("text/plain")) {
        result.append("\n").append(bodyPart.getContent());
        break; // without break same text appears twice in my tests
      } else if (bodyPart.isMimeType("text/html")) {
        String html = (String) bodyPart.getContent();
        result.append("\n").append(Jsoup.parse(html).text());
      } else if (bodyPart.getContent() instanceof MimeMultipart) {
        result.append(getTextFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
      }
    }
    return result.toString();
  }

  @RequiredArgsConstructor
  private enum MailCountFilter {
    total(Folder::getMessageCount),
    recent(folder -> folder.search(new FlagTerm(new Flags(Flags.Flag.RECENT), true)).length),
    deleted(folder -> folder.search(new FlagTerm(new Flags(Flags.Flag.DELETED), true)).length),
    unread(folder -> folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false)).length);
    private final ThrowingFunction<Folder, Integer, Exception> countFn;
  }

  @AllArgsConstructor
  private enum MailApplyHandler {
    update_add_file((workspaceBlock, mailBuilder) -> {
      Object input = workspaceBlock.getInput(VALUE, true);
      if (input instanceof String) {
        String mediaURL = (String) input;
        if (mediaURL.startsWith("http")) {
          mailBuilder.withURLAttachment(mediaURL);
        } else {
          Path path = Paths.get(mediaURL);
          if (Files.isRegularFile(path)) {
            mailBuilder.withFileAttachment(mediaURL);
          } else {
            writeAsByteArray(workspaceBlock, mailBuilder);
          }
        }
      } else {
        writeAsByteArray(workspaceBlock, mailBuilder);
      }
    });

    private final ThrowingBiConsumer<WorkspaceBlock, MailBuilder, Exception> applyFn;

    private static void writeAsByteArray(WorkspaceBlock workspaceBlock, MailBuilder mailBuilder) throws IOException {
      Path attachment = Files.createTempFile("mail_attachment_" + workspaceBlock.hashCode(), "tmp");
      Files.write(attachment, workspaceBlock.getInputByteArray(VALUE));
      mailBuilder.withFileAttachment(attachment.toString());
    }
  }

  private class POPHandler {

    private final Map<String, ThrowingConsumer<Store, Exception>> registeredHandlers = new HashMap<>();
    private final EntityContextBGP.ThreadContext<Void> refreshTask;
    private final MailEntity mailEntity;

    public POPHandler(MailEntity mailEntity) {
      this.mailEntity = mailEntity;
      this.refreshTask = entityContext.bgp().builder(mailEntity.getEntityID() + "-mail-fetch-task")
          .delay(Duration.ofSeconds(5)).interval(Duration.ofSeconds(mailEntity.getPop3RefreshTime()))
          .execute(this::refresh);
    }

    private void refresh() {
      connectToMailServerAndHandle(mailEntity, store -> {
        for (ThrowingConsumer<Store, Exception> handler : registeredHandlers.values()) {
          handler.accept(store);
        }
        return null;
      }, null);
    }
  }
}
